package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.common.Reply;

import java.io.PrintStream;
import java.util.List;

/**
 * Read-decide-write without locks: WATCH the keys, read them, queue the writes in MULTI, EXEC.
 * EXEC returns null if a watched key changed in between; then retry. WATCH needs a connection of
 * its own, which {@code withLeasedConnection} provides.
 */
public final class OptimisticLocking implements Example {

    @Override
    public String name() {
        return "watch";
    }

    @Override
    public String summary() {
        return "WATCH/MULTI/EXEC: move a balance between accounts with retries";
    }

    /** Moves {@code amount} from one account to another; false if funds are insufficient. */
    boolean transfer(JRedisClient client, String from, String to, long amount) {
        return client.withLeasedConnection(conn -> {
            for (int attempt = 1; attempt <= 10; attempt++) {
                conn.sync().watch("balance:" + from, "balance:" + to);
                String current = conn.sync().get("balance:" + from);
                long balance = current == null ? 0 : Long.parseLong(current);
                if (balance < amount) {
                    conn.sync().unwatch();
                    return false;
                }
                List<Reply> r = conn.sync().exec(conn.multi()
                        .send("DECRBY", "balance:" + from, amount)
                        .send("INCRBY", "balance:" + to, amount));
                if (r != null) {
                    return true;                 // committed atomically
                }
                // null: somebody changed a watched key after we read it, so read again and retry
            }
            throw new IllegalStateException("too much contention on " + from);
        });
    }

    @Override
    public void run(JRedisClient client, PrintStream out) throws Exception {
        client.sync().del("balance:alice", "balance:bob");
        client.sync().set("balance:alice", "100");
        client.sync().set("balance:bob", "20");

        out.println("transfer 30 alice->bob -> " + transfer(client, "alice", "bob", 30));
        out.println("transfer 500           -> " + transfer(client, "alice", "bob", 500) + " (insufficient funds)");

        // Concurrent transfers from several threads never lose money.
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 10; i++) {
                    transfer(client, "bob", "alice", 1);
                    transfer(client, "alice", "bob", 1);
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        long a = Long.parseLong(client.sync().get("balance:alice"));
        long b = Long.parseLong(client.sync().get("balance:bob"));
        out.println("after 80 concurrent transfers: alice=" + a + " bob=" + b + " total=" + (a + b));
    }
}
