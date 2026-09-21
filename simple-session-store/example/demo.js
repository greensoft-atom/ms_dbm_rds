const path = require("node:path");
const { SessionStore } = require("../src");

async function main() {
    const store = new SessionStore({
        file: path.join(__dirname, "../data/sessions.jsonl"),
        flushInterval: 1000,
        cleanupInterval: 1000,
        snapshotInterval: 10_000
    });

    await store.start();

    store.set("session:alice", {
        userId: 123,
        username: "alice",
        role: "admin"
    }, {
        ttl: 30
    });

    console.log("GET:", store.get("session:alice"));
    console.log("TTL:", store.ttl("session:alice"), "seconds");
    console.log("SIZE:", store.size());

    store.touch("session:alice", 60);

    await store.flush();

    console.log("Keys:", store.keys());

    await store.close();
}

main().catch(err => {
    console.error(err);
    process.exitCode = 1;
});
