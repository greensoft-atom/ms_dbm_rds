# Simple Session Store V1.1

A small Redis-like persistent session/key-value store written with pure Node.js/CommonJS and no runtime dependencies.

## Requirements

- Node.js 18+
- No npm dependencies

## Features

- In-memory `Map`
- `set`, `get`, `delete`, `has`
- TTL / expiration
- `touch`, `expire`, `ttl`
- session metadata
- asynchronous JSONL journal persistence
- batched writes
- filesystem `sync()` after journal writes
- atomic snapshot writes
- snapshot filesystem synchronization
- serialized append/flush/snapshot/compaction operations
- periodic expiration cleanup
- periodic snapshots
- journal compaction
- startup recovery
- ignores incomplete trailing JSONL records
- graceful shutdown
- HTTP cookie-based session example
- normal correctness tests
- 10,000-session stress test
- 50,000-session snapshot/restart stress test
- configurable million-session stress runner

## Install

There are no dependencies:

```bash
npm test
```

## Basic usage

```js
const { SessionStore } = require("./src");

const store = new SessionStore({
    file: "./data/sessions.jsonl",
    flushInterval: 1000,
    cleanupInterval: 1000,
    snapshotInterval: 60000
});

await store.start();

store.set("session:123", {
    userId: 123,
    role: "admin"
}, {
    ttl: 3600
});

console.log(store.get("session:123"));
console.log(store.ttl("session:123"));

await store.close();
```

## Persistence model

The live data is in memory:

```text
Map
 |
 +-- set/get/delete
 |
 +-- TTL
 |
 +-- metadata
 |
 +-- pending journal records
       |
       +-- sessions.jsonl
       |
       +-- periodic snapshot
```

JSONL stores operations:

```json
{"seq":1,"ts":1750000000000,"op":"set","key":"abc","value":{...}}
{"seq":2,"ts":1750000001000,"op":"delete","key":"abc"}
```

The snapshot stores complete state plus `lastSeq`.

Startup:

1. Load snapshot.
2. Replay journal records newer than `lastSeq`.
3. Remove expired sessions.
4. Start timers.

## Crash-safety improvements

The persistence path now deliberately uses several safeguards.

### 1. Journal writes are synced

A journal append is:

```text
write()
  ↓
fsync / handle.sync()
  ↓
close()
```

So `flush()` does not report success until Node has asked the filesystem to persist the data.

### 2. Failed flushes are retried

The previous version had an important failure mode: it removed records from the pending queue before writing them. If the filesystem write failed, those records could disappear from the persistence queue.

The new implementation puts the failed batch back into the queue.

### 3. Snapshot is written to a temporary file

```text
snapshot.tmp
    ↓
write
    ↓
fsync
    ↓
atomic rename
    ↓
snapshot.json
```

A crash during snapshot creation therefore leaves the previous snapshot intact.

### 4. Snapshot happens before journal compaction

The sequence is:

```text
flush journal
     ↓
write snapshot.tmp
     ↓
fsync snapshot.tmp
     ↓
rename snapshot.tmp → snapshot.json
     ↓
sync directory
     ↓
compact journal
     ↓
fsync compact journal
     ↓
rename compact journal
     ↓
sync directory
```

If the process dies between snapshot replacement and journal replacement, the old journal is still safe because records at or below the snapshot's `lastSeq` are ignored during replay.

### 5. Persistence operations are serialized

This prevents the particularly dangerous race:

```text
snapshot reads journal
       ↓
flush appends new record
       ↓
snapshot replaces journal
       ↓
new record accidentally disappears
```

All filesystem mutations go through one persistence chain:

```text
flush ───────┐
snapshot ────┼──→ serialized filesystem operations
compaction ──┘
```

New `set()` operations can still happen in memory while persistence is running. Their records remain pending and are persisted by the next flush.

## Important limitation

This is an embedded single-process store, not a Redis replacement.

Do not point multiple independent Node.js processes at the same journal/snapshot files.

Values must be JSON-serializable.

The current implementation also keeps the entire session set in memory. A million sessions with large session objects can require substantial RAM.

## Stress testing

Normal tests include 10,000 sessions and a 50,000-session snapshot/restart test:

```bash
npm test
```

The 50,000 test can be changed:

```bash
STRESS_COUNT=100000 npm test
```

For a dedicated million-session run:

```bash
node test/stress-million.js
```

Or:

```bash
STRESS_COUNT=2000000 node test/stress-million.js
```

The million-session test:

1. Creates the requested number of sessions.
2. Flushes them in 100,000-record batches.
3. Creates a snapshot.
4. Closes the store.
5. Creates a new store.
6. Restores the sessions.
7. Verifies the first, middle, and last records.
8. Reports insertion, snapshot, and restore timings.

Do not run the million-session test on a machine with limited RAM without monitoring memory usage.

## Run the HTTP example

```bash
npm start
```

Then open:

```text
http://localhost:3000/
```

The server creates a cookie-backed session.

## Tests

```bash
npm test
```
