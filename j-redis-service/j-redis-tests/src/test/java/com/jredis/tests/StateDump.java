package com.jredis.tests;

import com.jredis.client.AsyncCommands;
import com.jredis.common.Reply;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * The whole keyspace as sorted text, read through ordinary commands (pipelined), so two servers,
 * or one server before and after a restart, can be compared with a single equals. The caller must
 * be the only writer while it runs.
 */
final class StateDump {

    private StateDump() {
    }

    static Map<String, String> of(AsyncCommands c) {
        Set<String> keys = new LinkedHashSet<>();          // SCAN may return a key twice
        String cursor = "0";
        do {
            Reply r = c.send("SCAN", cursor, "COUNT", 1_000).join();
            cursor = r.asList().get(0).asString();
            for (Reply k : r.asList().get(1).asList()) {
                keys.add(k.asString());
            }
        } while (!cursor.equals("0"));
        List<String> order = new ArrayList<>(keys);
        List<CompletableFuture<Reply>> types = new ArrayList<>();
        for (String k : order) {
            types.add(c.send("TYPE", k));
        }
        List<CompletableFuture<Reply>> values = new ArrayList<>();
        List<CompletableFuture<Reply>> ttls = new ArrayList<>();
        List<String> typeNames = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            String k = order.get(i);
            String type = types.get(i).join().asString();
            typeNames.add(type);
            switch (type) {
                case "string": values.add(c.send("GET", k)); break;
                case "hash": values.add(c.send("HGETALL", k)); break;
                case "list": values.add(c.send("LRANGE", k, 0, -1)); break;
                case "set": values.add(c.send("SMEMBERS", k)); break;
                case "zset": values.add(c.send("ZRANGE", k, 0, -1, "WITHSCORES")); break;
                case "none": values.add(null); break;       // expired between SCAN and TYPE
                default: throw new IllegalStateException("unexpected type " + type);
            }
            ttls.add(c.send("PEXPIRETIME", k));
        }
        Map<String, String> out = new TreeMap<>();
        for (int i = 0; i < order.size(); i++) {
            if (values.get(i) == null) {
                continue;
            }
            Reply v = values.get(i).join();
            String value;
            switch (typeNames.get(i)) {
                case "string": value = v.asString(); break;
                case "hash": {
                    Map<String, String> m = new TreeMap<>();
                    List<Reply> l = v.asList();
                    for (int j = 0; j < l.size(); j += 2) {
                        m.put(l.get(j).asString(), l.get(j + 1).asString());
                    }
                    value = m.toString();
                    break;
                }
                case "set": {
                    List<String> m = new ArrayList<>();
                    for (Reply e : v.asList()) {
                        m.add(e.asString());
                    }
                    Collections.sort(m);
                    value = m.toString();
                    break;
                }
                case "list": {
                    List<String> m = new ArrayList<>();
                    for (Reply e : v.asList()) {
                        m.add(e.asString());
                    }
                    value = m.toString();
                    break;
                }
                default: value = R.render(v);
            }
            long ttl = ttls.get(i).join().asLong();
            out.put(order.get(i), typeNames.get(i) + " " + value + (ttl >= 0 ? " @" + ttl : ""));
        }
        return out;
    }
}
