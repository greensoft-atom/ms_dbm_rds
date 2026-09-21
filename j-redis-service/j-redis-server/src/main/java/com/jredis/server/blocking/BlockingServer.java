package com.jredis.server.blocking;

import com.jredis.common.NumberCodec;
import com.jredis.common.RespWriter;
import com.jredis.server.core.Client;
import com.jredis.server.core.Engine;
import com.jredis.server.db.Db;
import com.jredis.server.db.KeyEntry;
import com.jredis.server.db.ListValue;
import com.jredis.server.db.SkipList;
import com.jredis.server.db.ZSetValue;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * Serves a blocked client from a key that just became ready: performs its pop, propagates the
 * non-blocking effect (LPOP, LMOVE, ZPOPMIN...) and writes its reply.
 */
public final class BlockingServer {

    private static final byte[] LPOP = ascii("LPOP");
    private static final byte[] RPOP = ascii("RPOP");
    private static final byte[] LMOVE = ascii("LMOVE");
    private static final byte[] LEFT = ascii("LEFT");
    private static final byte[] RIGHT = ascii("RIGHT");
    private static final byte[] ZPOPMIN = ascii("ZPOPMIN");
    private static final byte[] ZPOPMAX = ascii("ZPOPMAX");

    private BlockingServer() {
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * @return true if the waiter was served (it must then be unblocked), false if the key cannot
     *         serve it (empty or of another type) — it keeps waiting
     */
    public static boolean tryServe(Engine engine, Client waiter, byte[] key) {
        BlockState s = waiter.block;
        Db db = engine.db();
        KeyEntry e = db.lookupRead(key);
        switch (s.op) {
            case BlockState.BLPOP:
            case BlockState.BRPOP: {
                if (e == null || e.type() != KeyEntry.LIST || e.list().size() == 0) {
                    return false;
                }
                boolean left = s.op == BlockState.BLPOP;
                db.prepareWrite(e);
                ListValue list = e.list();
                byte[] v = left ? list.pollFirst() : list.pollLast();
                if (list.size() == 0) {
                    db.deleteEntry(e);
                } else {
                    db.signalModified(key);
                }
                engine.propagate(new byte[][]{left ? LPOP : RPOP, key});
                ByteBuf out = engine.replyBuffer(waiter);
                RespWriter.arrayHeader(out, 2);
                RespWriter.bulk(out, key);
                RespWriter.bulk(out, v);
                return true;
            }
            case BlockState.BLMOVE: {
                if (e == null || e.type() != KeyEntry.LIST || e.list().size() == 0) {
                    return false;
                }
                KeyEntry dst = db.lookupRead(s.destination);
                if (dst != null && dst.type() != KeyEntry.LIST) {
                    engine.writeError(waiter, "WRONGTYPE Operation against a key holding the wrong kind of value");
                    return true;
                }
                byte[] v = moveElement(db, e, key, s.destination, s.fromLeft, s.toLeft);
                engine.propagate(new byte[][]{LMOVE, key, s.destination, s.fromLeft ? LEFT : RIGHT, s.toLeft ? LEFT : RIGHT});
                RespWriter.bulk(engine.replyBuffer(waiter), v);
                return true;
            }
            case BlockState.BZPOPMIN:
            case BlockState.BZPOPMAX: {
                if (e == null || e.type() != KeyEntry.ZSET || e.zset().size() == 0) {
                    return false;
                }
                boolean min = s.op == BlockState.BZPOPMIN;
                db.prepareWrite(e);
                ZSetValue z = e.zset();
                SkipList.Node n = min ? z.list().first() : z.list().last();
                byte[] member = n.member();
                double score = n.score();
                z.remove(member);
                if (z.size() == 0) {
                    db.deleteEntry(e);
                } else {
                    db.signalModified(key);
                }
                engine.propagate(new byte[][]{min ? ZPOPMIN : ZPOPMAX, key});
                ByteBuf out = engine.replyBuffer(waiter);
                RespWriter.arrayHeader(out, 3);
                RespWriter.bulk(out, key);
                RespWriter.bulk(out, member);
                RespWriter.bulk(out, NumberCodec.formatDoubleBytes(score));
                return true;
            }
            default:
                throw new IllegalStateException("unknown blocking operation " + s.op);
        }
    }

    /**
     * Pops from {@code src} and pushes to {@code dst} (creating it if needed). The caller has
     * checked that src is a non-empty list and dst is absent or a list.
     */
    public static byte[] moveElement(Db db, KeyEntry src, byte[] srcKey, byte[] dstKey, boolean fromLeft, boolean toLeft) {
        db.prepareWrite(src);
        ListValue from = src.list();
        byte[] v = fromLeft ? from.pollFirst() : from.pollLast();
        boolean sameKey = java.util.Arrays.equals(srcKey, dstKey);
        if (sameKey) {
            if (toLeft) {
                from.addFirst(v);
            } else {
                from.addLast(v);
            }
            db.signalModified(srcKey);
            return v;
        }
        if (from.size() == 0) {
            db.deleteEntry(src);
        } else {
            db.signalModified(srcKey);
        }
        KeyEntry dst = db.lookupWrite(dstKey);
        ListValue to;
        if (dst == null) {
            to = new ListValue();
            db.add(dstKey, KeyEntry.LIST, to);
        } else {
            to = dst.list();
        }
        if (toLeft) {
            to.addFirst(v);
        } else {
            to.addLast(v);
        }
        db.signalModified(dstKey);
        return v;
    }
}
