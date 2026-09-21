package com.jredis.server.persist;

import com.jredis.server.db.HashValue;
import com.jredis.server.db.KeyEntry;
import com.jredis.server.db.ListValue;
import com.jredis.server.db.SetValue;
import com.jredis.server.db.SipHash;
import com.jredis.server.db.SkipList;
import com.jredis.server.db.ZSetValue;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

/**
 * The base file: a point-in-time image of the dataset.
 *
 * <pre>
 * header   "JRDB"  u16 version=1  u16 flags=0  i64 createdAtMillis
 * record   u8 opcode (1 STRING, 2 HASH, 3 LIST, 4 SET, 5 ZSET)
 *          u8 flags (bit 0: has TTL)  [i64 expireAt]  varint keyLen, key
 *          body: STRING varint len, bytes | HASH varint n, n x (field, value)
 *                LIST varint n, n x element | SET varint n, n x member
 *                ZSET varint n, n x (member, f64 score) in ascending score order
 * trailer  u8 0xFF, u32 CRC32 of every preceding byte (including the 0xFF)
 * </pre>
 * Integers are big-endian; varints are unsigned LEB128.
 */
public final class BaseFormat {

    static final byte[] MAGIC = "JRDB".getBytes(StandardCharsets.US_ASCII);
    static final int VERSION = 1;
    static final int OP_EOF = 0xFF;
    private static final long MAX_LENGTH = 1L << 31;

    private BaseFormat() {
    }

    static void writeHeader(ByteSink s, long createdAtMillis) {
        s.writeBytes(MAGIC);
        s.writeShort(VERSION);
        s.writeShort(0);
        s.writeLong(createdAtMillis);
    }

    /** Encodes one key with its current value. */
    static void writeRecord(ByteSink s, KeyEntry e) {
        byte type = e.type();
        s.writeByte(type + 1);
        long expireAt = e.expireAt();
        s.writeByte(expireAt >= 0 ? 1 : 0);
        if (expireAt >= 0) {
            s.writeLong(expireAt);
        }
        s.writeLenBytes(e.key);
        switch (type) {
            case KeyEntry.STRING:
                s.writeLenBytes(e.stringValue());
                break;
            case KeyEntry.HASH:
                s.writeVarint(e.hash().size());
                e.hash().forEach(f -> {
                    s.writeLenBytes(f.field());
                    s.writeLenBytes(f.value());
                });
                break;
            case KeyEntry.LIST: {
                ListValue l = e.list();
                s.writeVarint(l.size());
                for (int i = 0; i < l.size(); i++) {
                    s.writeLenBytes(l.get(i));
                }
                break;
            }
            case KeyEntry.SET:
                s.writeVarint(e.set().size());
                e.set().forEach(m -> s.writeLenBytes(m.key));
                break;
            case KeyEntry.ZSET: {
                ZSetValue z = e.zset();
                s.writeVarint(z.size());
                for (SkipList.Node n = z.list().first(); n != null; n = n.next()) {
                    s.writeLenBytes(n.member());
                    s.writeLong(Double.doubleToRawLongBits(n.score()));
                }
                break;
            }
            default:
                throw new IllegalStateException("unknown type " + type);
        }
    }

    static void writeEof(ByteSink s) {
        s.writeByte(OP_EOF);
    }

    /** Receives each record while reading. The value is detached (not yet stored anywhere). */
    public interface RecordConsumer {
        void record(byte type, byte[] key, long expireAt, Object value) throws DataLoadException;
    }

    /**
     * Reads and verifies a base file.
     *
     * @return the number of records
     */
    public static long read(Path file, SipHash hasher, RecordConsumer consumer) throws IOException, DataLoadException {
        CRC32 crc = new CRC32();
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 20);
             CheckedInputStream checked = new CheckedInputStream(raw, crc)) {
            DataInputStream in = new DataInputStream(checked);
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new DataLoadException(file + " is not a base file (bad magic)");
            }
            int version = in.readUnsignedShort();
            if (version > VERSION) {
                throw new DataLoadException(file + " has format version " + version + "; this server reads up to " + VERSION);
            }
            in.readUnsignedShort();                 // flags, reserved
            in.readLong();                          // createdAt
            long records = 0;
            while (true) {
                int op = in.readUnsignedByte();
                if (op == OP_EOF) {
                    long computed = crc.getValue();
                    int stored = new DataInputStream(raw).readInt();   // read outside the checksum
                    if ((int) computed != stored) {
                        throw new DataLoadException(file + " is corrupt: CRC mismatch");
                    }
                    if (raw.read() != -1) {
                        throw new DataLoadException(file + " is corrupt: unexpected bytes after the trailer");
                    }
                    return records;
                }
                if (op < 1 || op > 5) {
                    throw new DataLoadException(file + " is corrupt: unknown record type " + op + " after " + records + " records");
                }
                byte type = (byte) (op - 1);
                int flags = in.readUnsignedByte();
                long expireAt = (flags & 1) != 0 ? in.readLong() : -1;
                byte[] key = readLenBytes(in, file);
                Object value = readValue(in, type, hasher, file);
                consumer.record(type, key, expireAt, value);
                records++;
            }
        } catch (EOFException e) {
            throw new DataLoadException(file + " is truncated or corrupt (ends before its trailer)", e);
        }
    }

    private static Object readValue(DataInputStream in, byte type, SipHash hasher, Path file) throws IOException, DataLoadException {
        switch (type) {
            case KeyEntry.STRING:
                return readLenBytes(in, file);
            case KeyEntry.HASH: {
                long n = readCount(in, file);
                HashValue h = new HashValue(hasher);
                for (long i = 0; i < n; i++) {
                    h.put(readLenBytes(in, file), readLenBytes(in, file));
                }
                return h;
            }
            case KeyEntry.LIST: {
                long n = readCount(in, file);
                ListValue l = new ListValue();
                for (long i = 0; i < n; i++) {
                    l.addLast(readLenBytes(in, file));
                }
                return l;
            }
            case KeyEntry.SET: {
                long n = readCount(in, file);
                SetValue s = new SetValue(hasher);
                for (long i = 0; i < n; i++) {
                    s.add(readLenBytes(in, file));
                }
                return s;
            }
            default: {
                long n = readCount(in, file);
                ZSetValue z = new ZSetValue(hasher);
                for (long i = 0; i < n; i++) {
                    byte[] member = readLenBytes(in, file);
                    double score = Double.longBitsToDouble(in.readLong());
                    if (Double.isNaN(score)) {
                        throw new DataLoadException(file + " is corrupt: NaN score");
                    }
                    z.insert(member, score);
                }
                return z;
            }
        }
    }

    private static long readVarint(DataInputStream in) throws IOException {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = in.readUnsignedByte();
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
        }
        throw new IOException("varint too long");
    }

    private static long readCount(DataInputStream in, Path file) throws IOException, DataLoadException {
        long n = readVarint(in);
        if (n < 0 || n > MAX_LENGTH) {
            throw new DataLoadException(file + " is corrupt: implausible element count " + n);
        }
        return n;
    }

    private static byte[] readLenBytes(DataInputStream in, Path file) throws IOException, DataLoadException {
        long len = readVarint(in);
        if (len < 0 || len > Integer.MAX_VALUE - 16) {
            throw new DataLoadException(file + " is corrupt: implausible length " + len);
        }
        byte[] b = new byte[(int) len];
        in.readFully(b);
        return b;
    }
}
