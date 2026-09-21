package com.jredis.server.persist;

/**
 * The engine's view of persistence. All methods are called on the command thread; the
 * implementation hands bytes to its own writer threads.
 */
public interface Persistence {

    boolean enabled();

    /** Logs a command's effect (buffered until the end of a transaction when inside one). */
    void propagate(byte[][] effect);

    void beginTransaction();

    void endTransaction();

    /** Marks the start of a top-level command, so its effects can be dropped on fail-stop. */
    void markCommandStart();

    /** Drops effects logged since {@link #markCommandStart} (and any open transaction). */
    void discardSinceMark();

    /** End of a command batch: hand the accumulated effects to the writer. */
    void endOfBatch();

    /** False while the AOF cannot be written; write commands then fail with MISCONF. */
    boolean writable();

    /** Background slice: snapshot progress and automatic rewrite triggers. */
    void background(long deadlineNanos);

    /** True while a rewrite is running (the background loop keeps going without idling). */
    boolean busy();

    /** BGSAVE SCHEDULE while a rewrite runs: start another when it ends. */
    default void scheduleRewrite() {
    }

    /** True if {@link #background} could do useful work right now (not merely waiting for the writer). */
    default boolean canProgress() {
        return busy();
    }

    boolean rewriteInProgress();

    /** Starts a background rewrite. @return null if started, otherwise the reason it was not */
    String startRewrite();

    void abortRewrite(String reason);

    /** Synchronous rewrite (SAVE, SHUTDOWN SAVE): blocks the command thread until done. */
    void saveBlocking() throws Exception;

    long lastSaveSeconds();

    /** Clean shutdown: write and fsync everything, stop writer threads. Blocking. */
    void shutdown();

    /** Fail-stop: write and fsync effects of completed commands, as far as possible. */
    void emergencyFlush();

    void appendInfo(StringBuilder sb);

    /** DEBUG RELOAD: rewrite, then rebuild the dataset from the files (a persistence self-test). */
    void debugReload() throws Exception;
}
