# j-redis-service

A self-hosted, Redis-compatible, in-memory data store written in **Java 8**.
It gives backend services fast shared state (sessions, one-time tokens,
caches, counters, rankings, queues, locks, pub/sub) without installing any
third-party Redis server, client library or tool. It comes with its own Java
client, a command-line client, tools and runnable examples.

**Version 1.0.0** (2026-09-21): implemented, reviewed in depth, and tested. See
[What was verified](#what-was-verified) and [CHANGELOG.md](CHANGELOG.md).

---

## At a glance

| | |
|---|---|
| Runtime | Java 8 or newer, one JVM process. The build targets Java 8, and every jar is verified to be Java 8 bytecode. |
| Protocol | RESP2 (the Redis wire protocol) over TCP, port **6379** by default |
| Data types | String, Hash, List, Set, Sorted Set; a TTL on any key |
| Features | 151 commands with Redis 7.2 semantics: pub/sub, blocking pops, `MULTI`/`EXEC`/`WATCH`, `SCAN`, and 3 extensions (`J.ZAROUND` for "my rank and neighbours", `J.CAS`/`J.CAD` for safe locks) |
| Execution | Netty I/O threads parse requests; **one command thread** owns all data and runs every command in order, so there are no locks on data |
| Persistence | Append-only file with background compaction (no `fork()`); fsync every second (configurable); a clean stop loses nothing |
| Clients | `j-redis-client` (async + blocking API, automatic pipelining, reconnects), `j-redis-cli`, and an embedded mode for tests |
| Platforms | Linux for production (systemd unit included); Windows and Linux for development |
| Dependencies | Netty 4.1, SLF4J/Logback, JCTools, HdrHistogram, all in the offline bundle [`../java8-offline`](../java8-offline/) |

## Quick start

**1. Build** (JDK 8 and Maven 3.9; offline setup in [guide 2](docs/guide/02-offline-repository.md)):

```bash
cd j-redis-service
mvn clean install                      # about 3 minutes including tests
scripts/make-dist.sh --skip-build      # Windows: powershell -ExecutionPolicy Bypass -File scripts\make-dist.ps1 -SkipBuild
```

**2. Run** the server from the distribution in `target/dist/j-redis-1.0.0/`:

```bash
bin/j-redis-server conf/j-redis-dev.conf          # Windows: bin\j-redis-server.cmd conf\j-redis-dev.conf
```

**3. Use** it, in a second terminal:

```bash
bin/j-redis-cli ping                               # PONG
bin/j-redis-cli set greeting "hello"               # OK
bin/j-redis-examples -p 6379                       # 15 runnable examples against this server
```

**4. From Java:**

```java
JRedisClient redis = JRedisClient.builder()
        .address("127.0.0.1", 6379)
        .clientName("orders-service")
        .build()
        .start();

redis.sync().set("user:42:name", "Ada");
redis.zincrby("lb:weekly:points", 10, "user:42");                 // async: CompletableFuture
Long rank = redis.sync().zrevrank("lb:weekly:points", "user:42");  // blocking facade

redis.close();
```

## Guides

Practical, step-by-step documentation in [`docs/guide/`](docs/guide/):

| # | Guide | For when you want to… |
|---|---|---|
| 1 | [Set up a development machine](docs/guide/01-setup.md) | install JDK 8 and Maven on Windows or Linux, configure an IDE |
| 2 | [Build with the java8-offline repository](docs/guide/02-offline-repository.md) | build without internet access, using the offline bundle |
| 3 | [Build, test and package](docs/guide/03-build-and-test.md) | compile, run tests, check the Java 8 guarantee, build the distribution |
| 4 | [Run and operate the server](docs/guide/04-run-and-operate.md) | configure, secure, deploy with systemd, back up, monitor, use the CLI and tools |
| 5 | [Use it from Java (client guide)](docs/guide/05-client-guide.md) | write application code: every data type, transactions, locks, queues, pub/sub, errors, testing |
| 6 | [Develop the service itself](docs/guide/06-developer-guide.md) | find your way around the code, add a command, write tests, debug |
| 7 | [Upgrade](docs/guide/07-upgrade-guide.md) | upgrade an installation, bump the version, update a dependency or the JDK |

## Examples

[`j-redis-examples`](j-redis-examples/src/main/java/com/jredis/examples/) has one
class per topic: quick start, strings and counters, cache-aside, sessions,
leaderboards, rate limiting, distributed locks, optimistic locking with
`WATCH`, one-time tokens, a reliable work queue, pub/sub, a service registry,
async pipelining, `SCAN`, and error handling. The build runs all of them on
every test run. They are described in
[guide 5 §5.15](docs/guide/05-client-guide.md#515-the-runnable-examples).

## Design documents

How and why it works. Read the primer first if Redis is new to you.

| # | Document | What it covers |
|---|---|---|
| 00 | [Redis primer](docs/00-redis-primer.md) | What Redis is, how it works inside, why it is fast |
| 01 | [Requirements and scope](docs/01-requirements-and-scope.md) | Goals, non-goals, constraints, numeric targets |
| 02 | [Architecture](docs/02-architecture.md) | Threads, request lifecycle, the command loop, backpressure |
| 03 | [Protocol](docs/03-protocol.md) | RESP2 framing, parser, limits, number formats, error conventions |
| 04 | [Data structures](docs/04-data-structures.md) | The keyspace `Dict`, `SCAN`, value types, the skip list, memory accounting |
| 05 | [Commands](docs/05-commands.md) | Every command, how it is logged for persistence, deviations from Redis |
| 06 | [Expiry and memory](docs/06-expiry-and-memory.md) | TTLs, active expiry, `maxmemory`, heap sizing |
| 07 | [Pub/Sub, blocking, transactions](docs/07-pubsub-blocking-transactions.md) | The features that change a client's state |
| 08 | [Persistence](docs/08-persistence.md) | Files, the fork-free snapshot and why it is correct, recovery, backups |
| 09 | [Integration patterns](docs/09-integration-patterns.md) | Extension commands, recipes, key naming, connections per process |
| 10 | [Client library](docs/10-client-library.md) | How `j-redis-client` works inside |
| 11 | [Operations and security](docs/11-operations-and-security.md) | All directives, `INFO` fields, runbook, security |
| 12 | [Testing strategy](docs/12-testing-strategy.md) | Reference model, crash tests, what is implemented |
| 13 | [Implementation plan](docs/13-implementation-plan.md) | Modules, milestones, status |
| 14 | [Decision log](docs/14-decision-log.md) | Every significant choice and why |

It replaces the "memstore" part of the earlier backend design
([../docs/04-memstore.md](../docs/04-memstore.md)).

## Repository layout

```
j-redis-service/
├── pom.xml                  parent: Java 8, pinned dependency and plugin versions (the only place the version is set)
├── j-redis-common/          RESP codec, replies, strict number and glob handling, build version
├── j-redis-server/          the server (main class com.jredis.server.Main)
├── j-redis-client/          the Java client library
├── j-redis-embedded/        the real server in-process, for tests
├── j-redis-cli/             command-line client
├── j-redis-tools/           benchmark, check-aof, dump
├── j-redis-examples/        runnable examples (tested in every build)
├── j-redis-tests/           integration, model-based, persistence, regression and crash tests
├── dist/                    launchers (sh + cmd), sample configs, systemd unit
├── scripts/                 make-dist.sh / .ps1, set-version.sh / .ps1
├── docs/                    design documents; docs/guide/ has the guides
└── CHANGELOG.md
```

## What was verified

| Check | Result |
|---|---|
| Tests | **133 tests pass** with `mvn clean install -Pfull` on JDK 8: 21 codec and number tests, 18 data-structure tests, 5 example tests, and 89 integration tests. The integration tests cover commands, the protocol over TCP, persistence, the client library (embedded and TCP), the model-based test, the review regressions, and the `kill -9` crash test. |
| Review | Six independent reviews (protocol, data structures, commands, persistence, engine, client) found 60+ defects, mostly in failure and edge paths. All were fixed, each with a regression test; see [CHANGELOG.md](CHANGELOG.md) and decisions D-26 to D-30 |
| Model-based test | 300,000 random commands over 3 seeds agree reply-for-reply with a naive reference model; the full keyspace is compared every 500 commands |
| Snapshot consistency | A base file written in slow slices while writes continue equals the state at the moment the rewrite started |
| Crash test | 5 × `kill -9` of a server process under a transactional workload, rewrites included: every transaction whole or absent. A kill lost at most the last few milliseconds of acknowledged writes (0 to 8 transactions per kill so far), within the documented 2 s bound (D-22). |
| Offline build | Builds and passes all tests from the `java8-offline` bundle as a read-only mirror, from an empty local repository ([guide 2](docs/guide/02-offline-repository.md)) |
| Java 8 | Every class in every jar, dependencies included, is major version ≤ 52 |
| Engine throughput | 505,000 pipelined `SET`/s on one connection |
| Latency | Not measured meaningfully yet. The development VM is shared and heavily loaded, and thread wake-ups there cost about 0.3 ms. Measure on the production machine ([guide 4 §4.10](docs/guide/04-run-and-operate.md#410-the-tools)). |
| Big keys during a rewrite | About 20–30 ms per 100k-member sorted set, 120–250 ms per million ([08 §6](docs/08-persistence.md#6-the-big-key-caveat)) |

**Not verified yet:** the Windows `.cmd`/`.ps1` scripts (written for Windows,
but no Windows machine was available), the production performance targets,
and the 24-hour soak test.
