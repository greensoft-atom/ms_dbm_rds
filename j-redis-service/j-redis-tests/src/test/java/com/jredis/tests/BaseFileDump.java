package com.jredis.tests;

import com.jredis.common.NumberCodec;
import com.jredis.server.db.HashValue;
import com.jredis.server.db.KeyEntry;
import com.jredis.server.db.ListValue;
import com.jredis.server.db.SetValue;
import com.jredis.server.db.SipHash;
import com.jredis.server.db.SkipList;
import com.jredis.server.db.ZSetValue;
import com.jredis.server.persist.BaseFormat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reads a base file into the {@link StateDump} format. */
final class BaseFileDump {

    private BaseFileDump() {
    }

    private static String s(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    static Map<String, String> of(Path file) throws Exception {
        Map<String, String> out = new TreeMap<>();
        BaseFormat.read(file, SipHash.random(), (type, key, expireAt, value) -> {
            String t;
            String v;
            switch (type) {
                case KeyEntry.STRING:
                    t = "string";
                    v = s((byte[]) value);
                    break;
                case KeyEntry.HASH: {
                    t = "hash";
                    Map<String, String> m = new TreeMap<>();
                    ((HashValue) value).forEach(f -> m.put(s(f.field()), s(f.value())));
                    v = m.toString();
                    break;
                }
                case KeyEntry.LIST: {
                    t = "list";
                    List<String> l = new ArrayList<>();
                    ListValue lv = (ListValue) value;
                    for (int i = 0; i < lv.size(); i++) {
                        l.add(s(lv.get(i)));
                    }
                    v = l.toString();
                    break;
                }
                case KeyEntry.SET: {
                    t = "set";
                    List<String> l = new ArrayList<>();
                    ((SetValue) value).forEach(m -> l.add(s(m.key)));
                    Collections.sort(l);
                    v = l.toString();
                    break;
                }
                default: {
                    t = "zset";
                    List<String> l = new ArrayList<>();
                    for (SkipList.Node n = ((ZSetValue) value).list().first(); n != null; n = n.next()) {
                        l.add(s(n.member()));
                        l.add(NumberCodec.formatDouble(n.score()));
                    }
                    v = "[" + String.join(", ", l) + "]";
                }
            }
            out.put(s(key), t + " " + v + (expireAt >= 0 ? " @" + expireAt : ""));
        });
        return out;
    }
}
