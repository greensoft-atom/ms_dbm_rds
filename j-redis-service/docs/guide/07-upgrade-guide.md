# 7 — Upgrade

This guide covers four kinds of upgrade: a running installation to a new
j-redis version, the project's own version number, a dependency, and the JDK.
It also explains which data formats must stay compatible.

## 7.1 Versions

Versions are `MAJOR.MINOR.PATCH`:

| Part | Changes when | Data directory | Clients |
|---|---|---|---|
| PATCH (1.0.**1**) | bug fixes only | read and written unchanged | unchanged |
| MINOR (1.**1**.0) | new commands, directives or features | the new version reads older data; the old version may not read data written by the new one (§7.3) | old clients keep working |
| MAJOR (**2**.0.0) | incompatible changes | read the release notes; a migration may be needed | read the release notes |

Every release is listed in [../../CHANGELOG.md](../../CHANGELOG.md), and a
changed file format is always called out there.

The running version: `j-redis-server --version`, `INFO server`
(`jredis_version`), or `CLIENT LIST` for connected clients' library version
(`lib-ver`).

## 7.2 Upgrade an installation

### Linux (systemd)

The layout from [guide 4 §4.4](04-run-and-operate.md#44-production-on-linux-systemd)
keeps each version in its own directory with a symlink to the current one, so
switching back and forth is quick:

```bash
# 0. read CHANGELOG.md for the new version: new directives? format changes?

# 1. back up the data (guide 4 §4.8)
sudo -u jredis /usr/local/bin/backup-j-redis.sh /var/backups/j-redis

# 2. unpack the new version next to the old one
sudo tar -xzf j-redis-1.1.0.tar.gz -C /opt

# 3. compare the shipped config and unit with yours, and apply new settings you want
diff /opt/j-redis-1.1.0/conf/j-redis.conf /etc/j-redis/j-redis.conf
diff /opt/j-redis-1.1.0/systemd/j-redis.service /etc/systemd/system/j-redis.service

# 4. switch (a clean stop: nothing is lost)
sudo systemctl stop j-redis
sudo ln -sfn /opt/j-redis-1.1.0 /opt/j-redis
sudo systemctl start j-redis

# 5. verify
/opt/j-redis/bin/j-redis-cli -a "$SECRET" info server | grep jredis_version
/opt/j-redis/bin/j-redis-cli -a "$SECRET" dbsize
journalctl -u j-redis --since "5 min ago"
```

Clients see the connection drop and reconnect by themselves, typically within
a few seconds. Requests in flight at that moment fail with
`JRedisConnectionException`.

**Rollback.** If the release notes say the data formats did not change (always
true for a PATCH release), stop the server, point the symlink back, and start
it. Otherwise restore the backup from step 1 into `/var/lib/j-redis` before
starting the old version. Writes made since the upgrade are then lost.

### Windows (development)

Stop the server (Ctrl+C), unpack the new distribution into a new folder, copy
your `conf` changes over, and start it with the same `dir`.

### Clients and server: which first?

The protocol (RESP2) does not change within a major version, so any client
1.x works with any server 1.x, **as long as the server knows every command
the client sends**. Upgrade the **server first** when a new client version, or
new application code, uses a command that only the new server has. Update the
`j-redis-client` version in your services' POMs as usual.

## 7.3 Data format compatibility

Three formats are persisted. Each is versioned or self-describing:

| File | Format | Version check |
|---|---|---|
| `manifest` | text, `format 1` line | a newer format is refused: `unsupported manifest format N (this server reads format 1)` |
| `base.N.jrdb` | binary, header `JRDB` + version (1) + CRC32 trailer | a newer version is refused: `has format version N; this server reads up to 1` |
| `incr.N.aof` | RESP commands (the logged effects) | an older server fails to replay a command it does not know |

In both refusal cases the server does not start (exit code 3) and nothing is
changed, so the operator can go back.

**Rules for developers** changing a format (see also
[guide 6 §6.7](06-developer-guide.md#67-before-you-hand-in-a-change)):

1. Only raise a format version together with code that still **reads the old
   version**. Within a major version, never drop reading an older format.
2. A new command should log effects that older servers understand, as the
   worked example in guide 6 does (`J.HPOP` logs `HDEL`). Where that is
   impossible, it is a format change: note it in the CHANGELOG, because a
   rollback then needs the backup.
3. Add a test that loads data written in the old format, keeping a small
   sample data directory in the test resources.

## 7.4 Upgrade a dependency

All versions live in the parent `pom.xml`: the `<properties>` section and
`<dependencyManagement>`, plus the plugin versions in `<pluginManagement>`.

1. **Check that the new version still supports Java 8.** Some lines stopped:

   | Library | Java 8 line | Newer lines need |
   |---|---|---|
   | Logback | 1.3.x | 1.4+: Java 11 |
   | JUnit | 5.x | 6.x: Java 17 |
   | Netty | 4.1.x | stay on 4.1.x for Java 8 |

   Read the library's release notes. Then check the bytecode as below: the
   jar's class files are the final word.
2. **Get the artifacts.** With internet access, the build downloads them. For
   offline machines, follow [guide 2 §2.8](02-offline-repository.md#28-when-something-is-missing)
   to add them to the `java8-offline` repository.
3. **Change the version** in the parent POM, for example
   `<netty.version>4.1.123.Final</netty.version>`.
4. **Verify**: `mvn clean install -Pfull` passes, and every jar is still Java 8
   ([guide 3 §3.4](03-build-and-test.md#34-check-the-java-8-guarantee)). For
   Netty, also run the benchmark before and after on the same machine
   ([guide 4 §4.10](04-run-and-operate.md#410-the-tools)).
5. **Record it**: the constraint table in
   [../01-requirements-and-scope.md](../01-requirements-and-scope.md) (C-6), and
   `CHANGELOG.md`.

## 7.5 Upgrade the JDK

**Building** stays on JDK 8. Compiling with a newer JDK and `-source 8
-target 8` still compiles against the newer class library, so code can end up
calling methods that do not exist on Java 8. The classic case is
`ByteBuffer.flip()`, which then fails at run time with `NoSuchMethodError`.
Use JDK 8 for builds (`mvn -v` must show 1.8). If the build must ever move to a
newer JDK, replace `<source>`/`<target>` with `<release>8</release>` in the
compiler plugin configuration.

**Running** on a newer Java (11, 17, 21) is not tested yet. The code uses only
standard APIs and Netty 4.1, which supports those versions, so it is expected
to work. Before relying on it:

1. Run the test suite on the new runtime while still compiling with JDK 8.
   Surefire's `jvm` property picks the JVM for the tests:
   `mvn clean install -Pfull -Djvm=/opt/jdk17/bin/java`.
2. Run the benchmark and a long test on that runtime.
3. Adjust the GC flags in the systemd unit: `-Xloggc` and `-XX:+PrintGCDetails`
   were replaced by `-Xlog:gc*:file=…` in Java 9+.

**JDK 8 updates** (8u*n*) need no change: install the update and restart the
server.

## 7.6 Bump the project version

The version is defined **only in the POMs**. The server's `--version`/`INFO`
and the client's `lib-ver` read it from the build. One command changes every
POM:

```bash
scripts/set-version.sh 1.1.0                                                 # Linux
```

```bat
powershell -ExecutionPolicy Bypass -File scripts\set-version.ps1 -Version 1.1.0 & rem Windows
```

Then:

1. Update `CHANGELOG.md`: move "Unreleased" to `1.1.0 — <date>`, and mention
   format changes and new directives.
2. `mvn clean install -Pfull`, and check the Java 8 guarantee.
3. `scripts/make-dist.sh` (or `make-dist.ps1`) → `target/dist/j-redis-1.1.0.tar.gz` / `.zip`.
4. Tag the source in version control (`git tag v1.1.0`).
5. Services that use the client update `<version>` in their POMs.

Use `-SNAPSHOT` versions (`1.1.0-SNAPSHOT`) between releases, so that a
development build can never be mistaken for a release.

## 7.7 Release checklist

- [ ] `mvn clean install -Pfull` green on JDK 8 (Linux; also the fast tier on Windows)
- [ ] every jar Java 8 (guide 3 §3.4)
- [ ] `CHANGELOG.md` complete, format changes called out
- [ ] docs updated (commands 05, directives 11, decisions 14, guides)
- [ ] distribution built and smoke-tested: start, `ping`, `j-redis-examples -p <port>`, clean stop
- [ ] upgrade tested on a copy of production data: old version → new version → the data is intact
- [ ] rollback plan decided (symlink back, or restore the backup)
