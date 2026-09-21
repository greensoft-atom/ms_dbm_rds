const fs = require("node:fs");
const fsp = fs.promises;
const path = require("node:path");

class SessionStore {
    constructor(options = {}) {
        this.file = options.file || "./data/sessions.jsonl";
        this.snapshotFile =
            options.snapshotFile || `${this.file}.snapshot.json`;

        this.flushInterval = options.flushInterval ?? 1000;
        this.cleanupInterval = options.cleanupInterval ?? 1000;
        this.snapshotInterval = options.snapshotInterval ?? 60_000;

        this.autoFlush = options.autoFlush !== false;
        this.autoCleanup = options.autoCleanup !== false;
        this.autoSnapshot = options.autoSnapshot !== false;

        this.sessions = new Map();

        this.started = false;
        this.dirty = false;
        this.nextSeq = 1;
        this.lastPersistedSeq = 0;

        this.pendingRecords = [];
        this.flushPromise = null;
        this.persistenceChain = Promise.resolve();
        this.timers = [];
    }

    async start() {
        if (this.started) return;

        await fsp.mkdir(path.dirname(path.resolve(this.file)), {
            recursive: true
        });

        await this._loadSnapshot();
        await this._loadJournal();

        this.started = true;

        if (this.autoFlush) {
            this.timers.push(setInterval(() => {
                this.flush().catch(err => this.emitError(err));
            }, this.flushInterval));
        }

        if (this.autoCleanup) {
            this.timers.push(setInterval(() => {
                this.cleanupExpired().catch(err => this.emitError(err));
            }, this.cleanupInterval));
        }

        if (this.autoSnapshot) {
            this.timers.push(setInterval(() => {
                this.snapshot().catch(err => this.emitError(err));
            }, this.snapshotInterval));
        }
    }

    async close() {
        if (!this.started) return;

        for (const timer of this.timers) {
            clearInterval(timer);
        }
        this.timers = [];

        await this.flush();
        await this.snapshot();

        this.started = false;
    }

    emitError(err) {
        if (typeof this.onError === "function") {
            this.onError(err);
        } else {
            console.error("[SessionStore]", err);
        }
    }

    _assertStarted() {
        if (!this.started) {
            throw new Error(
                "SessionStore has not been started. Call await store.start()."
            );
        }
    }

    _now() {
        return Date.now();
    }

    _normalizeTTL(ttl) {
        if (ttl === undefined || ttl === null) return null;

        if (!Number.isFinite(ttl) || ttl < 0) {
            throw new TypeError(
                "ttl must be a non-negative number of seconds."
            );
        }

        return Math.floor(ttl * 1000);
    }

    _buildSession(value, ttl, previous = null) {
        const now = this._now();
        const ttlMs = this._normalizeTTL(ttl);

        return {
            createdAt: previous?.createdAt ?? now,
            updatedAt: now,
            accessedAt: now,
            expiresAt:
                ttlMs === null
                    ? previous?.expiresAt ?? null
                    : now + ttlMs,
            accessCount: previous?.accessCount ?? 0,
            data: value
        };
    }

    _isExpired(session, now = this._now()) {
        return session.expiresAt !== null && session.expiresAt <= now;
    }

    _record(op, key, value) {
        const record = {
            seq: this.nextSeq++,
            ts: this._now(),
            op,
            key
        };

        if (value !== undefined) record.value = value;

        this.pendingRecords.push(record);
        this.dirty = true;

        return record.seq;
    }

    set(key, value, options = {}) {
        this._assertStarted();

        if (typeof key !== "string" || key.length === 0) {
            throw new TypeError("key must be a non-empty string.");
        }

        const previous = this.sessions.get(key);
        const session = this._buildSession(value, options.ttl, previous);

        this.sessions.set(key, session);
        this._record("set", key, session);

        return true;
    }

    get(key, options = {}) {
        this._assertStarted();

        const session = this.sessions.get(key);

        if (!session) return null;

        if (this._isExpired(session)) {
            this.sessions.delete(key);
            this._record("delete", key);
            return null;
        }

        session.accessedAt = this._now();
        session.accessCount += 1;

        if (options.touch === true) {
            this._record("touch", key, {
                accessedAt: session.accessedAt,
                accessCount: session.accessCount,
                expiresAt: session.expiresAt,
                updatedAt: session.updatedAt
            });
        }

        return session.data;
    }

    getSession(key) {
        this._assertStarted();

        const session = this.sessions.get(key);

        if (!session) return null;

        if (this._isExpired(session)) {
            this.sessions.delete(key);
            this._record("delete", key);
            return null;
        }

        return {
            ...session,
            data: session.data
        };
    }

    has(key) {
        return this.getSession(key) !== null;
    }

    delete(key) {
        this._assertStarted();

        const existed = this.sessions.delete(key);

        if (existed) this._record("delete", key);

        return existed;
    }

    touch(key, ttl) {
        this._assertStarted();

        const session = this.sessions.get(key);

        if (!session || this._isExpired(session)) {
            if (session) {
                this.sessions.delete(key);
                this._record("delete", key);
            }
            return false;
        }

        const ttlMs = this._normalizeTTL(ttl);
        const now = this._now();

        session.updatedAt = now;
        session.accessedAt = now;
        session.expiresAt = ttlMs === null ? null : now + ttlMs;

        this._record("touch", key, {
            accessedAt: session.accessedAt,
            updatedAt: session.updatedAt,
            expiresAt: session.expiresAt
        });

        return true;
    }

    expire(key, ttl) {
        return this.touch(key, ttl);
    }

    ttl(key) {
        this._assertStarted();

        const session = this.sessions.get(key);

        if (!session) return -2;

        if (this._isExpired(session)) {
            this.sessions.delete(key);
            this._record("delete", key);
            return -2;
        }

        if (session.expiresAt === null) return -1;

        return Math.max(
            0,
            Math.ceil((session.expiresAt - this._now()) / 1000)
        );
    }

    keys() {
        this._assertStarted();
        this._removeExpiredInMemory();
        return [...this.sessions.keys()];
    }

    values() {
        this._assertStarted();
        this._removeExpiredInMemory();
        return [...this.sessions.values()].map(session => session.data);
    }

    entries() {
        this._assertStarted();
        this._removeExpiredInMemory();

        return [...this.sessions.entries()].map(([key, session]) => [
            key,
            session.data
        ]);
    }

    size() {
        this._assertStarted();
        this._removeExpiredInMemory();
        return this.sessions.size;
    }

    clear() {
        this._assertStarted();

        for (const key of this.sessions.keys()) {
            this._record("delete", key);
        }

        this.sessions.clear();
    }

    async cleanupExpired() {
        this._assertStarted();

        const now = this._now();
        let removed = 0;

        for (const [key, session] of this.sessions) {
            if (this._isExpired(session, now)) {
                this.sessions.delete(key);
                this._record("delete", key);
                removed++;
            }
        }

        if (removed > 0 && !this.autoFlush) {
            await this.flush();
        }

        return removed;
    }

    _removeExpiredInMemory() {
        const now = this._now();

        for (const [key, session] of this.sessions) {
            if (this._isExpired(session, now)) {
                this.sessions.delete(key);
                this._record("delete", key);
            }
        }
    }

    /*
     * Serialize all filesystem mutations.
     *
     * This is important: a snapshot/compaction must never race an append.
     * Otherwise compaction can read an old journal, another flush can append
     * a new record, and the compaction rename could accidentally overwrite it.
     */
    _withPersistenceLock(task) {
        const run = this.persistenceChain.then(task, task);

        this.persistenceChain = run.catch(() => {});

        return run;
    }

    async flush() {
        this._assertStarted();

        if (this.flushPromise) return this.flushPromise;

        this.flushPromise = this._withPersistenceLock(async () => {
            if (this.pendingRecords.length === 0) {
                return;
            }

            // Detach this exact batch. New operations can continue to arrive
            // while the filesystem write is in progress.
            const records = this.pendingRecords;
            this.pendingRecords = [];

            const payload =
                records.map(record => JSON.stringify(record)).join("\n") +
                "\n";

            try {
                await this._appendAndSync(payload);

                this.lastPersistedSeq = Math.max(
                    this.lastPersistedSeq,
                    records[records.length - 1].seq
                );

                this.dirty = this.pendingRecords.length > 0;
            } catch (err) {
                // Critical: never lose an in-memory persistence batch when
                // the filesystem operation fails.
                this.pendingRecords = records.concat(this.pendingRecords);
                this.dirty = true;
                throw err;
            }
        });

        try {
            await this.flushPromise;
        } finally {
            this.flushPromise = null;
        }
    }

    async _appendAndSync(payload) {
        const filePath = path.resolve(this.file);

        await fsp.mkdir(path.dirname(filePath), { recursive: true });

        const handle = await fsp.open(filePath, "a");

        try {
            await handle.write(payload, null, "utf8");
            await handle.sync();
        } finally {
            await handle.close();
        }
    }

    async snapshot() {
        this._assertStarted();

        return this._withPersistenceLock(async () => {
            // Because this runs under the same lock, no flush/compaction can
            // mutate the journal while we are taking the snapshot.
            await this._flushLocked();

            const snapshotSeq = this.lastPersistedSeq;

            this._removeExpiredInMemory();

            const snapshot = {
                format: 1,
                createdAt: this._now(),
                lastSeq: snapshotSeq,
                sessions: {}
            };

            for (const [key, session] of this.sessions) {
                snapshot.sessions[key] = session;
            }

            const snapshotPath = path.resolve(this.snapshotFile);
            const tempPath = `${snapshotPath}.tmp`;

            await fsp.mkdir(path.dirname(snapshotPath), {
                recursive: true
            });

            await this._writeFileAndSync(
                tempPath,
                JSON.stringify(snapshot) + "\n"
            );

            // The new snapshot is durable before the journal is compacted.
            await fsp.rename(tempPath, snapshotPath);
            await this._syncDirectory(path.dirname(snapshotPath));

            await this._compactJournalLocked(snapshotSeq);

            this.dirty = this.pendingRecords.length > 0;
        });
    }

    async _flushLocked() {
        if (this.pendingRecords.length === 0) return;

        const records = this.pendingRecords;
        this.pendingRecords = [];

        const payload =
            records.map(record => JSON.stringify(record)).join("\n") + "\n";

        try {
            await this._appendAndSync(payload);

            this.lastPersistedSeq = Math.max(
                this.lastPersistedSeq,
                records[records.length - 1].seq
            );

            this.dirty = this.pendingRecords.length > 0;
        } catch (err) {
            this.pendingRecords = records.concat(this.pendingRecords);
            this.dirty = true;
            throw err;
        }
    }

    async _writeFileAndSync(filePath, content) {
        const handle = await fsp.open(filePath, "w");

        try {
            await handle.writeFile(content, "utf8");
            await handle.sync();
        } finally {
            await handle.close();
        }
    }

    async _syncDirectory(directory) {
        // Directory fsync is supported on Unix-like systems and gives us
        // stronger rename durability. Windows may reject opening directories,
        // in which case the file-level fsync/atomic rename still applies.
        try {
            const handle = await fsp.open(directory, "r");

            try {
                await handle.sync();
            } finally {
                await handle.close();
            }
        } catch (err) {
            if (process.platform !== "win32") {
                throw err;
            }
        }
    }

    async _compactJournalLocked(snapshotSeq) {
        const journalPath = path.resolve(this.file);

        if (!fs.existsSync(journalPath)) return;

        const content = await fsp.readFile(journalPath, "utf8");

        if (!content) return;

        const remaining = [];

        for (const line of content.split("\n")) {
            if (!line.trim()) continue;

            try {
                const record = JSON.parse(line);

                if (record.seq > snapshotSeq) {
                    remaining.push(JSON.stringify(record));
                }
            } catch {
                // A malformed final line can be the result of a process crash.
                // Snapshot already represents state through snapshotSeq.
            }
        }

        const tempPath = `${journalPath}.compact.tmp`;

        await this._writeFileAndSync(
            tempPath,
            remaining.length ? remaining.join("\n") + "\n" : ""
        );

        await fsp.rename(tempPath, journalPath);
        await this._syncDirectory(path.dirname(journalPath));
    }

    async _loadSnapshot() {
        if (!fs.existsSync(this.snapshotFile)) return;

        try {
            const text = await fsp.readFile(this.snapshotFile, "utf8");
            const snapshot = JSON.parse(text);

            if (snapshot.format !== 1 || !snapshot.sessions) {
                throw new Error("Unsupported snapshot format.");
            }

            this.sessions.clear();

            for (const [key, session] of Object.entries(snapshot.sessions)) {
                this.sessions.set(key, session);
            }

            this.lastPersistedSeq = Number(snapshot.lastSeq) || 0;
            this.nextSeq = this.lastPersistedSeq + 1;
        } catch (err) {
            throw new Error(
                `Failed to load session snapshot "${this.snapshotFile}": ${err.message}`
            );
        }
    }

    async _loadJournal() {
        if (!fs.existsSync(this.file)) return;

        const text = await fsp.readFile(this.file, "utf8");

        for (const line of text.split("\n")) {
            if (!line.trim()) continue;

            let record;

            try {
                record = JSON.parse(line);
            } catch {
                // Ignore a partially written trailing record.
                continue;
            }

            if (!Number.isFinite(record.seq)) continue;

            this.nextSeq = Math.max(this.nextSeq, record.seq + 1);

            if (record.seq <= this.lastPersistedSeq) continue;

            this._applyRecord(record);

            this.lastPersistedSeq = Math.max(
                this.lastPersistedSeq,
                record.seq
            );
        }

        this._removeExpiredInMemory();
    }

    _applyRecord(record) {
        switch (record.op) {
            case "set":
                this.sessions.set(record.key, record.value);
                break;

            case "delete":
                this.sessions.delete(record.key);
                break;

            case "touch": {
                const session = this.sessions.get(record.key);
                if (!session) break;

                if (record.value.expiresAt !== undefined) {
                    session.expiresAt = record.value.expiresAt;
                }

                if (record.value.updatedAt !== undefined) {
                    session.updatedAt = record.value.updatedAt;
                }

                if (record.value.accessedAt !== undefined) {
                    session.accessedAt = record.value.accessedAt;
                }

                if (record.value.accessCount !== undefined) {
                    session.accessCount = record.value.accessCount;
                }

                break;
            }

            default:
                // Forward-compatible: unknown operations are ignored.
                break;
        }
    }
}

module.exports = SessionStore;
