# 14 — Decision log

Each entry: the context, the decision, and what follows from it. "Supersedes"
names the part of [../../docs/04-memstore.md](../../docs/04-memstore.md) it
replaces. All entries are **accepted** as of 2026-09-21. D-18 to D-25 were
made during implementation (2026-09-21), each because a test or a measurement
showed the need.

---

### D-1 — RESP2 as the wire protocol
**Supersedes:** doc 04 §5 (custom binary protocol with request ids).

**Context.** We write both server and client, so the format is our choice.
Doc 04 proposed a custom binary protocol.

**Decision.** Use RESP2.

**Why.** A complete, proven specification we do not have to design, document,
or debug. Simple, binary-safe, readable in captures and over `telnet`. The AOF
reuses the same encoding. Loopback parsing cost differs negligibly from a custom
binary format; system calls dominate.

**Consequences.** No request ids: replies are correlated by order, so blocking
commands and pub/sub need their own connections, and a timed-out request stays
in the FIFO until its reply arrives ([10 §4](10-client-library.md#4-correlating-replies)).
Speaking RESP does not mean using third-party software (D-14).

---

### D-2 — One command thread; Netty for I/O

**Decision.** All data is owned and mutated by a single thread; Netty threads
only parse and write bytes.

**Why.** Atomicity of every command without locks; the model that made Redis
fast and simple. The estimated load (~5k ops/s) is 20× below what one thread
should sustain.

**Consequences.** One slow command delays everyone, so every long operation —
rehash, expiry, snapshot — is sliced ([02 §5](02-architecture.md#5-background-work)),
and big keys matter ([08 §6](08-persistence.md#6-the-big-key-caveat)).

---

### D-3 — Own hash table with incremental rehash and SipHash

**Decision.** `Dict`, not `java.util.HashMap`.

**Why.** `HashMap` resizes in one step — hundreds of milliseconds at 10M keys —
and offers no stable cursor for `SCAN`. SipHash with a random key resists
hash-flooding through user-chosen key text.

**Consequences.** More code to own and test; covered by property tests from M2.

---

### D-4 — Multi-part AOF as the only persistence mechanism
**Supersedes:** doc 04 §6 (AOF plus a separate snapshot file).

**Decision.** A base file, incremental files, and a manifest; a snapshot is
simply the base produced by a rewrite.

**Why.** One mechanism instead of two; atomic manifest replacement makes every
crash point recoverable ([08 §7](08-persistence.md#7-crash-analysis)).

---

### D-5 — Fork-free point-in-time snapshots by per-key copy-on-write
**Supersedes:** doc 04 §6 (`SNAPSHOT-CURSOR` markers).

**Context.** The JVM cannot `fork()`. Doc 04's scheme serialised keys in their
current state and tried to reconcile at load time, which is unsound for
multi-key commands ([08 §5.6](08-persistence.md#56-why-not-the-scheme-in-doc-04)).

**Decision.** Take a pre-image of each key just before its first change after
T; scan the rest; mark progress with an epoch per entry.

**Consequences.** The base is a true image at T and loading is plain replay.
Correctness depends on every write going through the `Db` hooks (D-2's
structure makes that enforceable). One large key is written in one piece.

---

### D-6 — Log effects, not commands

**Decision.** The AOF records deterministic effects: absolute expiry times,
results of float arithmetic, concrete members removed by random pops, `DEL` for
expirations.

**Why.** A replay hours later must reproduce exactly what happened.

---

### D-7 — Transactions yes, scripting no

**Decision.** Implement `MULTI`/`EXEC`/`DISCARD`/`WATCH`. No Lua, no Functions.

**Why.** `MULTI`/`EXEC` is cheap given D-2 and removes four of doc 04's custom
commands. A scripting engine is a large, security-sensitive dependency, and the
backend needs only three read-decide-write operations, provided as commands (D-8).

---

### D-8 — Standard commands first; `J.` extensions only where necessary
**Supersedes:** doc 04 §4 (seven custom commands).

**Decision.** Replace `CLAIM`, `ZADDMAX`, `HSETEXNX`, `LPUSHCAP`, `INCREX` with
standard commands and options (`ZADD GT`, `EXPIRE NX`, `SET NX EX`) and
`MULTI`/`EXEC`. Keep `J.ZAROUND`, `J.CAS`, `J.CAD` — standard Redis cannot do
these atomically in one round trip without Lua.

**Why.** Standard semantics are specified by the Redis documentation, not by us.
The `J.` prefix can never collide with a future Redis command name.

---

### D-9 — Disconnect slow subscribers
**Supersedes:** doc 04 §3 ("messages dropped").

**Decision.** A subscriber over its output limit is disconnected, not silently
skipped.

**Why.** Silent gaps are undetectable; a disconnect is visible, and the client
library reports the reconnect so the application can resynchronise.

---

### D-10 — `noeviction` only

**Decision.** Over `maxmemory`, writes fail with `-OOM`; nothing is evicted.

**Why.** Silently evicting a token or session becomes a mysterious application bug.
LRU is deferred to M8.

---

### D-11 — On-heap storage, no compact encodings in v1

**Decision.** Keys and values are ordinary Java objects; no off-heap memory, no
flat small-value encodings.

**Why.** Simplest correct design; the expected data set is well under 1 GB. Costs
about 1.5–2× Redis's memory. Compact encodings are an M8 option if measurement
shows a need.

---

### D-12 — Fail-stop after a partial mutation

**Decision.** An unexpected exception after a command started changing data
exits the process (code 4); before any change, it only fails the command.

**Why.** A half-applied change can corrupt a structure and poison every later
answer. The failing command was never logged, so restarting and replaying
yields the exact prior state ([02 §11](02-architecture.md#11-failure-handling-inside-a-command)).

---

### D-13 — Default port 6379
**Supersedes:** doc 04's port 6400.

**Decision.** Default to 6379; configurable.

**Why.** The conventional port for this protocol. Set `port 6400` to keep the
old value; no third-party Redis runs on the machines to collide with.

---

### D-14 — Our own client and tools only

**Decision.** No third-party Redis server, client library, or tool is used
anywhere — production, development, or testing. We build `j-redis-client`,
`j-redis-cli`, and `j-redis-benchmark`.

**Consequences.** No differential testing against real Redis. Correctness rests
on the documented Redis 7.2 semantics plus our own reference model
([12 §1](12-testing-strategy.md#1-the-oracle-problem)).

---

### D-15 — Clean-room implementation; Redis 7.2 semantics as reference

**Decision.** Implement from the public documentation of the protocol and
commands. Do not port Redis source code. Use Redis 7.2 behaviour as the
reference.

**Why.** 7.2 is the last BSD-licensed release and the base of Valkey, with
stable, widely documented behaviour. Later Redis source is under
RSALv2/SSPLv1/AGPLv3 ([00 §14](00-redis-primer.md#14-versions-licensing-forks)),
and working from documentation keeps the licensing question simple. Whether
anything more is needed is a question for legal counsel, not this document.

---

### D-16 — `appendfsync everysec` by default; `always` deferred

**Decision.** `everysec` and `no` in v1; `always` (with group commit) in M8.

**Why.** Durable business data lives in MongoDB. What j-redis holds is either
rebuildable or tolerates losing about a second, and producers of important results
spool must-not-lose data locally until acknowledged.

---

### D-17 — Deterministic time buckets for active expiry

**Decision.** One-second buckets with one registration per key, instead of
Redis's random sampling ([06 §3](06-expiry-and-memory.md#3-active-expiry)).

**Why.** Predictable memory reclamation and reproducible tests. Clients never
see the difference: lazy expiry guarantees an expired key is never read.

---

### D-18 — Resolved open questions

**Decision** (by the project owner):

| Question | Answer |
|---|---|
| Java packages | `com.jredis.server`, `com.jredis.client`, plus `common`, `embedded`, `cli`, `tools` |
| Port | 6379, configurable (D-13) |
| Largest leaderboard | Several with 100k+ members. They are supported; the rewrite stall per key is measured in [08 §6](08-persistence.md#6-the-big-key-caveat). |
| Persist on every write? | No. Persist periodically, and make the period configurable: `appendfsync everysec` with `appendfsync-interval-millis` (default 1000) |
| Team | One developer |
| Java level | Java 8 only: source, target and every dependency (bytecode major ≤ 52 is verified on the built jars) |

---

### D-19 — One command connection per calling thread

**Context.** With `commandConnections > 1`, sending each command to the next
connection in turn broke per-thread order. A thread's `INCR` #2 could run
before its `INCR` #1. The client test that found it runs 8 threads × 5,000
commands.

**Decision.** Each calling thread is pinned to one connection (thread id modulo
the count). It uses another connection only while its own is down.

**Why.** Code that sends `SET k` and then `EXPIRE k` without waiting must see
them run in that order. Spreading threads, not commands, keeps the load balanced
for services with many worker threads.

---

### D-20 — Registries use `java.util.HashMap`

**Decision.** Pub/Sub channels, blocked keys and `WATCH`ed keys live in
`HashMap<ByteKey, …>`, not in our `Dict`.

**Why.** They are small, never persisted and never `SCAN`ned. They are also
never large enough for a resize to pause the command thread noticeably.
`Dict`'s incremental rehash and reverse-binary cursor exist for the keyspace
only. `ByteKey` caches the hash of the key bytes.

---

### D-21 — Embedded mode over Netty's in-VM transport

**Supersedes:** the `ClientOutput`-queue design first sketched for
[10 §10](10-client-library.md#10-embedded-mode).

**Decision.** The embedded server listens on a `LocalServerChannel`, and its
client connects with `LocalChannel`. Both sides run the normal pipelines.

**Why.** Embedded tests then exercise the real protocol code, backpressure and
reply ordering, not a parallel path. Netty ships the transport, so it cost no
new code.

---

### D-22 — Replies are sent once the effect is queued for the AOF writer

**Decision.** A write is acknowledged when its effect is handed to the AOF
writer thread, before the `write(2)`. The writer writes continuously and
fsyncs every `appendfsync-interval-millis`.

**Why.** Waiting for the writer thread on every write would add a thread
hand-off to each write's latency. NFR-4 already allows losing up to about 2 s
of acknowledged writes on `kill -9` or power loss.

**Measured.** Runs of 5 × `kill -9` under a transactional workload lost 0 to 8
acknowledged transactions per kill (the last few milliseconds), and never split
a transaction. Redis writes
before replying, so a *process* crash of Redis loses nothing acknowledged. Ours
can lose the last few milliseconds. `appendfsync always` with group commit
(M8) would close this gap.

---

### D-23 — Expiry boundary: expired at `now >= expireAt`

**Decision.** Keep the rule from [06 §2](06-expiry-and-memory.md#2-lazy-expiry).
Redis 7.2 uses `now > expireAt`, so the two differ by one millisecond at the
boundary. This is listed in [05 §3](05-commands.md#3-deviations-from-redis-72).

**Why.** "A 10 s TTL is gone at 10 s" is easier to reason about, and no client
can observe a 1 ms difference reliably.

---

### D-24 — Tools ship as one program

**Decision.** `j-redis-tools benchmark | check-aof | dump` instead of three
programs; other documents still call them `j-redis-benchmark`,
`j-redis-check-aof` and `j-redis-dump`.

**Why.** One jar, one launcher, and the same client code for all three.

---

### D-25 — JMH microbenchmarks deferred

**Decision.** The microbenchmarks in [12 §9](12-testing-strategy.md#9-performance)
wait for the production box. Until then the property tests guard correctness,
and `j-redis-tools benchmark` plus the rewrite's `aof_rewrite_longest_record_ms`
give end-to-end numbers.

**Why.** Microbenchmark numbers from the shared development VM (load average
about 9 on 12 cores) would mislead more than they inform. JMH 1.37 is in the
offline bundle when needed.

---

## Decisions from the review (2026-09-21)

Six reviewers each went through one part of the code: protocol, data
structures, commands, persistence, engine and client. These decisions came out
of their findings. Every fixed defect has a regression test
([12 §12](12-testing-strategy.md#12-what-is-implemented-2026-09-21)).

### D-26 — Disabled commands stay replayable

**Decision.** `disable-command` marks a command instead of removing it.
Clients get "unknown command"; the AOF loader may still replay it. A name that
is not a command is a start-up error.

**Why.** Other commands log it as their effect: `FLUSHDB` logs `FLUSHALL`,
`RPOPLPUSH` logs `LMOVE`, expiry logs `DEL`. With the shipped production config
(`disable-command FLUSHALL`), one `FLUSHDB` made the next start fail. A typo
must not silently leave a dangerous command enabled.

### D-27 — A closing client is detached at once

**Decision.** When a client is killed, sends `QUIT`, has a protocol error, or
half-closes its connection, it leaves the blocking queues, its subscriptions
and its watches immediately. Its queued and deferred commands are dropped. The
rest is freed when the channel's close event arrives.

**Why.** Otherwise a `BLPOP` of a killed client could still be served. The
element would be popped, logged, and written to a dead socket: lost.

### D-28 — The AOF writer and rewrite fail safe

**Decisions.**
- Any failure while encoding a snapshot record aborts the rewrite.
- A dead base writer aborts it in any state.
- `SAVE` refuses at once while the AOF cannot be written.
- A failed truncate-back blocks writing until it succeeds.
- After the manifest names a new incr file, that file is never deleted.
- A failed `DEBUG RELOAD` is a fail-stop.
- Automatic rewrites back off (1 s doubling to 1 h) after a failure.

**Why.** The review found paths where a half-written base could be committed,
where a rewrite stayed "running" forever, and where a failure on a full disk
recurred every second.

### D-29 — `INCRBYFLOAT` uses exact decimal arithmetic

**Decision.** Add the two values as `BigDecimal` and print 17 fractional digits
with trailing zeros removed ([03 §7](03-protocol.md#7-number-formatting)).

**Why.** Redis computes in `long double`, so `0.1 + 0.2` is `0.3` there. Binary
`double` gave `0.30000000000000004`, which users would see as a bug.

### D-30 — Client connections: one lifecycle, no surprises

**Decisions.**
- `start()` is idempotent, and CLOSED is final.
- A lost `WATCH` fails the `EXEC`.
- Timeout checks cannot be cancelled by callbacks.
- `close()` works from a callback.
- DNS is resolved off the event loop.

**Why.** Each was a way for a caller to get a hang, a leak, a reply meant for
somebody else, or a transaction that committed unchecked.

