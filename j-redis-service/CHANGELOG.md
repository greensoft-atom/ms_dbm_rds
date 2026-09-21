# Changelog

All notable changes. Versions follow `MAJOR.MINOR.PATCH`
([docs/guide/07-upgrade-guide.md](docs/guide/07-upgrade-guide.md#71-versions)).
A change to a data format is always marked **Format**.

## 1.0.0 — 2026-09-21

First release.

### Server
- RESP2 server on Netty 4.1 (epoll on Linux, NIO elsewhere), default port
  6379. One command thread owns all data, and backpressure is applied per
  connection.
- 151 commands: strings, keys and TTLs, hashes, lists, sets, sorted sets,
  pub/sub, blocking pops, `MULTI`/`EXEC`/`WATCH`, server administration, and
  the extensions `J.ZAROUND`, `J.CAS` and `J.CAD` ([docs/05](docs/05-commands.md)).
- Persistence: a multi-part append-only file (manifest + base + incr) with
  fork-free, per-key copy-on-write rewrites. `appendfsync everysec` with a
  configurable interval (`appendfsync-interval-millis`).
- **Format:** manifest `format 1`; base file `JRDB` version 1; AOF records are
  RESP commands.
- Operations: `INFO`, `SLOWLOG`, `CONFIG GET/SET`, `CLIENT`, protected mode,
  `requirepass`, `disable-command`, `maxmemory` (no eviction), `maxclients`,
  idle `timeout`, and exit codes for systemd.

### Client and tools
- `j-redis-client`: an async API with automatic pipelining, a blocking facade,
  transactions, leased connections for `WATCH`, pub/sub with resubscription,
  blocking pops, reconnects with backoff, timeouts, and metrics.
- `j-redis-embedded` (the real server in-process, for tests), `j-redis-cli`,
  `j-redis-tools` (`benchmark`, `check-aof`, `dump`), and `j-redis-examples`
  (15 runnable, tested examples).
- A distribution for Linux and Windows (`scripts/make-dist.sh`,
  `scripts/make-dist.ps1`), sample configs and a systemd unit.

### Fixed before release (review of 2026-09-21)
An in-depth review of every area found and fixed, each with a regression test:

- **Data safety**
  - A server that could not restart after `FLUSHDB` when `FLUSHALL` was disabled.
  - A huge `SPOP` that made the AOF unloadable.
  - Replay dropping `APPEND`/`SETRANGE` data after `proto-max-bulk-len` was lowered.
  - A corrupt base that could be committed after a failure while encoding a key.
  - A rewrite stuck forever after a base-writer failure (`SAVE` hung the server).
  - A ROTATE failure that deleted the incr file the manifest names.
  - Mid-file AOF corruption after a failed truncate.
  - A failed `DEBUG RELOAD` that kept serving a partial dataset.
  - A data-directory lock lost to a second attempt in the same JVM.
- **Correctness**
  - A killed blocked client that could still be served; the element was lost.
  - A waiter of another type blocking the others on the same key.
  - `EXEC` running writes under OOM or MISCONF.
  - Read-only commands inside `EXEC` triggering a fail-stop.
  - `WATCH` on an already-expired key, and `FLUSHALL` aborting a `WATCH` on a missing key.
  - `CLIENT KILL` without a filter, or with `TYPE master`, killing everyone.
  - `CONFIG SET` applying half of its changes, and splitting values on spaces.
  - `INCRBYFLOAT` giving `0.30000000000000004`.
  - `RANDOMKEY` returning nil while live keys exist.
  - `LMOVE`/`SMOVE` error order, `SET`/`GETEX` repeated options, and a `SETRANGE` overflow.
- **Robustness**
  - Protocol errors reported repeatedly, and connections reset.
  - Replies lost for half-closed connections.
  - Length headers that wrapped around.
  - 4 MB allocated per connection for a 10-byte header.
  - The idle timeout cutting off slow transfers.
  - Unbounded error lines.
  - Deleted keys with a TTL kept in memory until their expiry.
  - Unkeyed hashing in the server's registries.
  - Idle CPU spinning, and a per-JVM shared clock.
- **Client**
  - A callback that could silently disable all later timeouts.
  - `close()` from a callback deadlocking.
  - A `WATCH` lost in a reconnect letting `EXEC` commit unchecked.
  - A second `start()` mixing up replies.
  - `close()` during a connect leaking a connection.
  - The blocking-pop queue wedging after a connection loss.
  - The benchmark hanging when the server went away.
  - `check-aof --fix` on a single file ignoring the lock.
