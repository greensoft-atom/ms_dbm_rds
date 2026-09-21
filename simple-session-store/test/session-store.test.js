const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const { SessionStore } = require("../src");

async function makeStore(options = {}) {
    const dir = await fs.mkdtemp(
        path.join(os.tmpdir(), "simple-session-store-")
    );

    const store = new SessionStore({
        file: path.join(dir, "sessions.jsonl"),
        snapshotFile: path.join(dir, "sessions.snapshot.json"),
        autoFlush: false,
        autoCleanup: false,
        autoSnapshot: false,
        ...options
    });

    await store.start();

    return { store, dir };
}

async function readLines(file) {
    try {
        const text = await fs.readFile(file, "utf8");

        return text
            .split("\n")
            .filter(Boolean)
            .map(line => JSON.parse(line));
    } catch (err) {
        if (err.code === "ENOENT") return [];
        throw err;
    }
}

test("set/get/has/delete", async () => {
    const { store } = await makeStore();

    store.set("abc", { userId: 123 });

    assert.deepEqual(store.get("abc"), { userId: 123 });
    assert.equal(store.has("abc"), true);
    assert.equal(store.delete("abc"), true);
    assert.equal(store.get("abc"), null);
    assert.equal(store.has("abc"), false);

    await store.close();
});

test("TTL expires a session", async () => {
    const { store } = await makeStore();

    store.set("abc", { hello: "world" }, { ttl: 0.05 });

    assert.equal(store.has("abc"), true);

    await new Promise(resolve => setTimeout(resolve, 80));

    assert.equal(store.get("abc"), null);
    assert.equal(store.has("abc"), false);

    await store.close();
});

test("touch extends TTL", async () => {
    const { store } = await makeStore();

    store.set("abc", { value: 1 }, { ttl: 0.05 });

    await new Promise(resolve => setTimeout(resolve, 25));

    assert.equal(store.touch("abc", 1), true);
    assert.ok(store.ttl("abc") >= 1);

    await store.close();
});

test("flush and restart restore sessions", async () => {
    const { store, dir } = await makeStore();

    store.set("abc", { userId: 42 });
    store.set("xyz", { userId: 99 });

    await store.flush();
    await store.close();

    const restored = new SessionStore({
        file: path.join(dir, "sessions.jsonl"),
        snapshotFile: path.join(dir, "sessions.snapshot.json"),
        autoFlush: false,
        autoCleanup: false,
        autoSnapshot: false
    });

    await restored.start();

    assert.deepEqual(restored.get("abc"), { userId: 42 });
    assert.deepEqual(restored.get("xyz"), { userId: 99 });

    await restored.close();
});

test("snapshot and restart restore sessions", async () => {
    const { store, dir } = await makeStore();

    store.set("abc", { userId: 42 });
    await store.flush();
    await store.snapshot();

    const restored = new SessionStore({
        file: path.join(dir, "sessions.jsonl"),
        snapshotFile: path.join(dir, "sessions.snapshot.json"),
        autoFlush: false,
        autoCleanup: false,
        autoSnapshot: false
    });

    await restored.start();

    assert.deepEqual(restored.get("abc"), { userId: 42 });

    await restored.close();
});

test("snapshot compaction preserves writes made after the snapshot point", async () => {
    const { store, dir } = await makeStore();

    store.set("before", { value: 1 });
    await store.flush();

    // snapshot has to contain "before"
    await store.snapshot();

    // This write happens after the snapshot. It must survive restart even
    // though the journal was compacted.
    store.set("after", { value: 2 });
    await store.flush();

    const restored = new SessionStore({
        file: path.join(dir, "sessions.jsonl"),
        snapshotFile: path.join(dir, "sessions.snapshot.json"),
        autoFlush: false,
        autoCleanup: false,
        autoSnapshot: false
    });

    await restored.start();

    assert.deepEqual(restored.get("before"), { value: 1 });
    assert.deepEqual(restored.get("after"), { value: 2 });

    await restored.close();
});

test("journal contains strictly increasing sequence numbers", async () => {
    const { store, dir } = await makeStore();

    for (let i = 0; i < 100; i++) {
        store.set(`key:${i}`, { i });
    }

    await store.flush();

    const records = await readLines(path.join(dir, "sessions.jsonl"));

    for (let i = 1; i < records.length; i++) {
        assert.ok(records[i].seq > records[i - 1].seq);
    }

    await store.close();
});

test("malformed trailing JSONL record does not prevent recovery", async () => {
    const { store, dir } = await makeStore();

    store.set("abc", { userId: 42 });
    await store.flush();
    await store.close();

    const journal = path.join(dir, "sessions.jsonl");
    await fs.appendFile(journal, '{"seq":999,"op":"set","key":"broken"', "utf8");

    const restored = new SessionStore({
        file: journal,
        snapshotFile: path.join(dir, "sessions.snapshot.json"),
        autoFlush: false,
        autoCleanup: false,
        autoSnapshot: false
    });

    await restored.start();

    assert.deepEqual(restored.get("abc"), { userId: 42 });

    await restored.close();
});

test("unknown journal operations are ignored", async () => {
    const { store, dir } = await makeStore();
    await store.close();

    await fs.appendFile(
        path.join(dir, "sessions.jsonl"),
        JSON.stringify({
            seq: 1,
            ts: Date.now(),
            op: "future-operation",
            key: "abc"
        }) + "\n"
    );

    const restored = new SessionStore({
        file: path.join(dir, "sessions.jsonl"),
        snapshotFile: path.join(dir, "sessions.snapshot.json"),
        autoFlush: false,
        autoCleanup: false,
        autoSnapshot: false
    });

    await restored.start();

    assert.equal(restored.get("abc"), null);

    await restored.close();
});

test("stress: 10,000 sessions set/get/delete", async () => {
    const { store } = await makeStore();

    const count = 10_000;

    for (let i = 0; i < count; i++) {
        store.set(`stress:${i}`, {
            userId: i,
            name: `user-${i}`,
            flags: {
                admin: i % 100 === 0,
                active: true
            }
        });
    }

    assert.equal(store.size(), count);

    for (let i = 0; i < count; i++) {
        assert.equal(store.get(`stress:${i}`).userId, i);
    }

    await store.flush();

    for (let i = 0; i < count; i += 2) {
        assert.equal(store.delete(`stress:${i}`), true);
    }

    assert.equal(store.size(), count / 2);

    await store.flush();
    await store.close();
});

test("stress: 50,000 sessions snapshot and restart", async () => {
    const { store, dir } = await makeStore();

    const count = Number(process.env.STRESS_COUNT || 50_000);

    for (let i = 0; i < count; i++) {
        store.set(`bulk:${i}`, {
            userId: i,
            value: `payload-${i}`
        });
    }

    assert.equal(store.size(), count);

    await store.flush();
    await store.snapshot();
    await store.close();

    const restored = new SessionStore({
        file: path.join(dir, "sessions.jsonl"),
        snapshotFile: path.join(dir, "sessions.snapshot.json"),
        autoFlush: false,
        autoCleanup: false,
        autoSnapshot: false
    });

    const start = process.hrtime.bigint();
    await restored.start();
    const elapsedMs =
        Number(process.hrtime.bigint() - start) / 1_000_000;

    assert.equal(restored.size(), count);
    assert.deepEqual(restored.get(`bulk:0`), {
        userId: 0,
        value: "payload-0"
    });
    assert.deepEqual(restored.get(`bulk:${count - 1}`), {
        userId: count - 1,
        value: `payload-${count - 1}`
    });

    console.log(
        `\n[stress] restored ${count.toLocaleString()} sessions in ${elapsedMs.toFixed(1)} ms`
    );

    await restored.close();
});
