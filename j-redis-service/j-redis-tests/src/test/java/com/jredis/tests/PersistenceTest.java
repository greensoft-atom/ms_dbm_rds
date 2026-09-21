package com.jredis.tests;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.EmbeddedConfig;
import com.jredis.embedded.JRedisEmbedded;
import com.jredis.server.core.ManualClock;
import com.jredis.server.persist.DataLoadException;
import com.jredis.server.persist.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistenceTest {

    private static final long T0 = EmbeddedTest.T0;

    @TempDir
    Path dir;

    @TempDir
    Path scratch;

    private final ManualClock clock = new ManualClock(T0);

    private JRedisEmbedded open(Path d, Consumer<com.jredis.server.config.ServerConfig> tune) {
        return JRedisEmbedded.start(EmbeddedConfig.persistentAt(d).clock(clock)
                .configure(c -> {
                    c.enableDebugCommand(true);
                    tune.accept(c);
                }));
    }

    private JRedisEmbedded open(Path d) {
        return open(d, c -> { });
    }

    private static String info(JRedisClient c, String field) {
        for (String line : c.sync().info("persistence").split("\r\n")) {
            if (line.startsWith(field + ":")) {
                return line.substring(field.length() + 1);
            }
        }
        throw new AssertionError("no INFO field " + field);
    }

    private static void awaitRewriteDone(JRedisClient c) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (!info(c, "aof_rewrite_in_progress").equals("0")) {
            assertThat(System.currentTimeMillis()).as("rewrite finished").isLessThan(deadline);
            Thread.sleep(5);
        }
        assertThat(info(c, "aof_last_rewrite_status")).isEqualTo("ok");
    }

    @Test
    void restartRecoversTheExactState() {
        Map<String, String> before;
        try (JRedisEmbedded r = open(dir)) {
            JRedisClient c = r.newClient();
            new Workload(1, 200).run(c, 30_000);
            before = StateDump.of(c);
            assertThat(before).hasSizeGreaterThan(300);
        }
        try (JRedisEmbedded r = open(dir)) {
            assertThat(StateDump.of(r.newClient())).isEqualTo(before);
        }
    }

    /**
     * The base file holds exactly the state at the moment BGREWRITEAOF ran, although writes keep
     * coming while it is produced in small slices; base + new incr equals the live state.
     */
    @Test
    void rewriteIsAConsistentSnapshotUnderConcurrentWrites() throws Exception {
        Map<String, String> atRewrite;
        Map<String, String> finalState;
        try (JRedisEmbedded r = open(dir, c -> {
            c.backgroundSliceMicros(20);
            c.backgroundMaxDuty(2);
        })) {
            JRedisClient c = r.newClient();
            for (int i = 0; i < 40_000; i++) {                       // bulk to make the snapshot take a while
                c.send("SET", "bulk:" + i, "value-" + i);
            }
            new Workload(2, 300).run(c, 20_000);
            atRewrite = StateDump.of(c);                                  // this test is the only writer
            assertThat(c.sync().send("BGREWRITEAOF").asString()).contains("started");
            Workload w = new Workload(3, 300);
            int writesDuring = 0;
            while (info(c, "aof_rewrite_in_progress").equals("1")) {
                w.run(c, 200);
                writesDuring += 200;
            }
            assertThat(writesDuring).as("writes while the snapshot was taken").isGreaterThan(0);
            awaitRewriteDone(c);
            w.run(c, 5_000);
            finalState = StateDump.of(c);
            Manifest m = Manifest.read(dir.resolve(Manifest.FILE));
            assertThat(m.base).isNotNull();
            assertThat(BaseFileDump.of(dir.resolve(m.base))).isEqualTo(atRewrite);
        }
        try (JRedisEmbedded r = open(dir)) {
            assertThat(StateDump.of(r.newClient())).isEqualTo(finalState);
        }
    }

    @Test
    void debugReloadAndSaveKeepTheState() {
        try (JRedisEmbedded r = open(dir)) {
            JRedisClient c = r.newClient();
            new Workload(4, 100).run(c, 10_000);
            Map<String, String> before = StateDump.of(c);
            assertThat(c.sync().send("DEBUG", "RELOAD").asString()).isEqualTo("OK");
            assertThat(StateDump.of(c)).isEqualTo(before);
            assertThat(c.sync().send("SAVE").asString()).isEqualTo("OK");
            assertThat(StateDump.of(c)).isEqualTo(before);
            assertThat(info(c, "aof_base_file")).startsWith("base.");
        }
    }

    @Test
    void expiredKeysStayExpiredAfterRestart() {
        try (JRedisEmbedded r = open(dir)) {
            JRedisClient c = r.newClient();
            c.sync().set("short", "x");
            c.sync().pexpire("short", 1_000);
            c.sync().set("long", "y");
            c.sync().pexpire("long", 100_000);
            c.sync().set("forever", "z");
        }
        clock.advance(5_000);
        try (JRedisEmbedded r = open(dir)) {
            JRedisClient c = r.newClient();
            assertThat(c.sync().get("short")).isNull();
            assertThat(c.sync().pttl("long")).isEqualTo(95_000);
            assertThat(c.sync().get("forever")).isEqualTo("z");
        }
    }

    /** A crash mid-write leaves a torn last record; loading keeps a prefix of the history. */
    @Test
    void aTornTailIsCutAndTheRestIsAPrefix() throws Exception {
        int n = 300;
        try (JRedisEmbedded r = open(dir)) {
            JRedisClient c = r.newClient();
            for (int i = 1; i <= n; i++) {
                c.sync().set("k:" + i, "v");
                c.sync().incr("counter");
            }
        }
        Manifest m = Manifest.read(dir.resolve(Manifest.FILE));
        Path incr = dir.resolve(m.lastIncr());
        long size = Files.size(incr);
        int checked = 0;
        for (long cut = size - 1; cut > size - 400; cut -= 3) {
            Path copy = Files.createTempDirectory(scratch, "torn");
            copyDir(dir, copy);
            try (FileChannel ch = FileChannel.open(copy.resolve(m.lastIncr()), StandardOpenOption.WRITE)) {
                ch.truncate(cut);
            }
            try (JRedisEmbedded r = open(copy)) {
                JRedisClient c = r.newClient();
                String counter = c.sync().get("counter");
                long cnt = counter == null ? 0 : Long.parseLong(counter);
                assertThat(cnt).isLessThan(n).isGreaterThan(n - 40);
                java.util.List<java.util.concurrent.CompletableFuture<Long>> present = new java.util.ArrayList<>();
                for (long i = 1; i <= cnt; i++) {
                    present.add(c.exists("k:" + i));
                }
                for (int i = 0; i < present.size(); i++) {
                    assertThat(present.get(i).join()).as("k:%d with counter %d", i + 1, cnt).isEqualTo(1);
                }
                assertThat(c.sync().exists("k:" + (cnt + 2))).isZero();
                c.sync().set("after", "recovery");                  // appending after the cut works
            }
            try (JRedisEmbedded r = open(copy)) {
                assertThat(r.newClient().sync().get("after")).isEqualTo("recovery");
            }
            checked++;
        }
        assertThat(checked).isGreaterThan(100);
    }

    @Test
    void aCorruptBaseFileRefusesToStart() throws Exception {
        try (JRedisEmbedded r = open(dir)) {
            JRedisClient c = r.newClient();
            new Workload(5, 100).run(c, 5_000);
            assertThat(c.sync().send("SAVE").asString()).isEqualTo("OK");
        }
        Path base = dir.resolve(Manifest.read(dir.resolve(Manifest.FILE)).base);
        flipByte(base, Files.size(base) / 2);
        assertThatThrownBy(() -> open(dir)).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(DataLoadException.class)
                .hasMessageContaining("corrupt");
    }

    @Test
    void garbageInTheMiddleOfAnIncrFileRefusesToStart() throws Exception {
        try (JRedisEmbedded r = open(dir)) {
            JRedisClient c = r.newClient();
            for (int i = 0; i < 1_000; i++) {
                c.sync().set("k" + i, "v" + i);
            }
        }
        Path incr = dir.resolve(Manifest.read(dir.resolve(Manifest.FILE)).lastIncr());
        byte[] data = Files.readAllBytes(incr);
        int mid = new String(data, StandardCharsets.ISO_8859_1).indexOf("\r\n*", data.length / 2) + 2;
        data[mid] = 'X';
        Files.write(incr, data);
        assertThatThrownBy(() -> open(dir)).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(DataLoadException.class)
                .hasMessageContaining("corrupt AOF");
    }

    @Test
    void aSecondServerCannotOpenTheSameDirectory() {
        try (JRedisEmbedded r = open(dir)) {
            assertThatThrownBy(() -> open(dir)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("in use");
            assertThat(r.fatalError()).isNull();
        }
    }

    /** Commands log effects such as FLUSHALL or SREM batches; replay must work whatever is disabled. */
    @Test
    void effectsOfDisabledCommandsAndBigSpopsReplay() {
        Map<String, String> before;
        try (JRedisEmbedded r = open(dir, c -> c.disabledCommands().add("FLUSHALL"))) {
            JRedisClient c = r.newClient();
            c.sync().set("gone", "x");
            c.sync().send("FLUSHDB");                               // logged as FLUSHALL
            Object[] sadd = new Object[2 + 5_000];
            sadd[0] = "SADD";
            sadd[1] = "s";
            for (int i = 0; i < 5_000; i++) {
                sadd[2 + i] = "m" + i;
            }
            c.sync().send(sadd);
            c.sync().send("SPOP", "s", 4_000);                      // logged as batched SREMs in MULTI/EXEC
            before = StateDump.of(c);
            assertThat(before.get("s")).startsWith("set ");
        }
        try (JRedisEmbedded r = open(dir, c -> c.disabledCommands().add("FLUSHALL"))) {
            assertThat(StateDump.of(r.newClient())).isEqualTo(before);
            assertThat(r.newClient().sync().scard("s")).isEqualTo(1_000);
        }
    }

    /** A lowered size limit must not make replay drop strings that were accepted when written. */
    @Test
    void replayIgnoresALoweredSizeLimit() {
        StringBuilder half = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            half.append('a');
        }
        try (JRedisEmbedded r = open(dir)) {
            JRedisClient c = r.newClient();
            c.sync().append("big", half.toString());
            c.sync().append("big", half.toString());
            c.sync().send("SETRANGE", "big2", 1_000, half.substring(0, 100));
        }
        try (JRedisEmbedded r = open(dir, c -> c.protoMaxBulkLen(1_024))) {
            JRedisClient c = r.newClient();
            assertThat(c.sync().strlen("big")).isEqualTo(1_200);
            assertThat(c.sync().strlen("big2")).isEqualTo(1_100);
        }
    }

    @Test
    void anUnknownDisabledCommandIsAStartupError() {
        assertThatThrownBy(() -> open(dir, c -> c.disabledCommands().add("FLUSHAL")))
                .hasMessageContaining("disable-command: unknown command 'FLUSHAL'");
    }

    /** A dead base writer used to leave the rewrite in "scanning" forever, and SAVE hung the server. */
    @Test
    void aFailingBaseWriterAbortsTheRewriteAndSaveReportsIt() throws Exception {
        try (JRedisEmbedded r = open(dir, c -> c.autoAofRewritePercentage(0))) {   // only the rewrites below
            JRedisClient c = r.newClient(b -> b.commandTimeoutMillis(60_000));
            StringBuilder mb = new StringBuilder();
            for (int i = 0; i < 1 << 20; i++) {
                mb.append('x');
            }
            for (int i = 0; i < 80; i++) {                          // more than the 64 MB snapshot backlog
                c.sync().set("big:" + i, mb.toString());
            }
            Files.createDirectories(dir.resolve("base.2.jrdb.tmp").resolve("blocker"));   // the base cannot be written
            assertThat(c.sync().send("BGREWRITEAOF").asString()).contains("started");
            long deadline = System.currentTimeMillis() + 30_000;
            while (!info(c, "aof_rewrite_in_progress").equals("0")) {
                assertThat(System.currentTimeMillis()).as("rewrite gave up").isLessThan(deadline);
                Thread.sleep(20);
            }
            assertThat(info(c, "aof_last_rewrite_status")).isEqualTo("aborted");
            Files.createDirectories(dir.resolve("base.3.jrdb.tmp").resolve("blocker"));
            assertThatThrownBy(() -> c.sync().send("SAVE")).hasMessageContaining("failed");
            assertThat(c.sync().ping()).isEqualTo("PONG");            // the server did not hang
            assertThat(c.sync().dbsize()).isEqualTo(80);
        }
        try (JRedisEmbedded r = open(dir)) {
            assertThat(r.newClient().sync().dbsize()).isEqualTo(80);
        }
    }

    @Test
    void bgsaveScheduleRunsAfterTheCurrentRewrite() throws Exception {
        try (JRedisEmbedded r = open(dir, c -> {
            c.backgroundSliceMicros(50);
            c.backgroundMaxDuty(1);
        })) {
            JRedisClient c = r.newClient();
            for (int i = 0; i < 20_000; i++) {
                c.send("SET", "k" + i, "v");
            }
            assertThat(c.sync().send("BGREWRITEAOF").asString()).contains("started");
            assertThat(c.sync().send("BGSAVE", "SCHEDULE").asString()).isEqualTo("Background saving scheduled");
            long deadline = System.currentTimeMillis() + 60_000;
            while (!info(c, "aof_generation").equals("3") || !info(c, "aof_rewrite_in_progress").equals("0")) {
                assertThat(System.currentTimeMillis()).as("the scheduled rewrite ran").isLessThan(deadline);
                Thread.sleep(20);
            }
        }
    }

    @Test
    void aSecondLockInTheSameProcessIsRefusedAndKeepsTheFirst() throws Exception {
        try (com.jredis.server.persist.DataDirLock first = com.jredis.server.persist.DataDirLock.acquire(dir)) {
            assertThat(first).isNotNull();
            assertThatThrownBy(() -> com.jredis.server.persist.DataDirLock.acquire(dir))
                    .isInstanceOf(com.jredis.server.persist.DataDirLockedException.class);
            assertThatThrownBy(() -> open(dir)).hasMessageContaining("in use");
        }
        try (JRedisEmbedded r = open(dir)) {                          // released: usable again
            assertThat(r.newClient().sync().ping()).isEqualTo("PONG");
        }
    }

    /** A crash during the very first start leaves an empty incr.1.aof and no manifest: still a fresh directory. */
    @Test
    void anInterruptedFirstStartIsRecovered() throws Exception {
        Files.createFile(dir.resolve("incr.1.aof"));
        try (JRedisEmbedded r = open(dir)) {
            r.newClient().sync().set("k", "v");
        }
        try (JRedisEmbedded r = open(dir)) {
            assertThat(r.newClient().sync().get("k")).isEqualTo("v");
        }
    }

    private static void flipByte(Path file, long offset) throws IOException {
        byte[] data = Files.readAllBytes(file);
        data[(int) offset] ^= 0x5A;
        Files.write(file, data);
    }

    private static void copyDir(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.list(from)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                if (!name.equals("LOCK") && !Files.isDirectory(p)) {
                    Files.copy(p, to.resolve(name));
                }
            }
        }
    }
}
