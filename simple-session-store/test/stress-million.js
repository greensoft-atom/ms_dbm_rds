const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const { SessionStore } = require("../src");

async function main() {
    const count = Number(process.env.STRESS_COUNT || 1_000_000);

    if (!Number.isInteger(count) || count <= 0) {
        throw new Error("STRESS_COUNT must be a positive integer.");
    }

    const dir = await fs.mkdtemp(
        path.join(os.tmpdir(), "simple-session-store-million-")
    );

    const store = new SessionStore({
        file: path.join(dir, "sessions.jsonl"),
        snapshotFile: path.join(dir, "sessions.snapshot.json"),
        autoFlush: false,
        autoCleanup: false,
        autoSnapshot: false
    });

    console.log(`Creating ${count.toLocaleString()} sessions...`);

    const start = process.hrtime.bigint();

    await store.start();

    for (let i = 0; i < count; i++) {
        store.set(`million:${i}`, {
            userId: i,
            value: i * 2
        });

        if ((i + 1) % 100_000 === 0) {
            console.log(
                `  inserted ${(i + 1).toLocaleString()} / ${count.toLocaleString()}`
            );
            await store.flush();
        }
    }

    await store.flush();

    const insertMs =
        Number(process.hrtime.bigint() - start) / 1_000_000;

    console.log(
        `Inserted ${count.toLocaleString()} sessions in ${insertMs.toFixed(1)} ms`
    );

    const snapshotStart = process.hrtime.bigint();
    await store.snapshot();

    const snapshotMs =
        Number(process.hrtime.bigint() - snapshotStart) / 1_000_000;

    console.log(`Snapshot: ${snapshotMs.toFixed(1)} ms`);

    await store.close();

    const restoreStart = process.hrtime.bigint();

    const restored = new SessionStore({
        file: path.join(dir, "sessions.jsonl"),
        snapshotFile: path.join(dir, "sessions.snapshot.json"),
        autoFlush: false,
        autoCleanup: false,
        autoSnapshot: false
    });

    await restored.start();

    const restoreMs =
        Number(process.hrtime.bigint() - restoreStart) / 1_000_000;

    console.log(
        `Restored ${restored.size().toLocaleString()} sessions in ${restoreMs.toFixed(1)} ms`
    );

    const samples = [
        0,
        Math.floor(count / 2),
        count - 1
    ];

    for (const i of samples) {
        const value = restored.get(`million:${i}`);

        if (
            !value ||
            value.userId !== i ||
            value.value !== i * 2
        ) {
            throw new Error(`Integrity check failed for million:${i}`);
        }
    }

    console.log("Integrity checks: PASS");

    await restored.close();

    console.log(`Temporary data: ${dir}`);
}

main().catch(err => {
    console.error(err);
    process.exitCode = 1;
});
