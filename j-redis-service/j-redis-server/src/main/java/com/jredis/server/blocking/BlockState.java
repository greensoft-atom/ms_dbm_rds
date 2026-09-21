package com.jredis.server.blocking;

/** Why and until when a client is blocked. */
public final class BlockState {

    public static final int BLPOP = 1;
    public static final int BRPOP = 2;
    public static final int BLMOVE = 3;
    public static final int BZPOPMIN = 4;
    public static final int BZPOPMAX = 5;

    public final int op;
    public final byte[][] keys;
    /** Absolute wall-clock deadline in ms; 0 = wait forever. */
    public final long timeoutAtMillis;
    /** BLMOVE only. */
    public final byte[] destination;
    public final boolean fromLeft;
    public final boolean toLeft;

    public BlockState(int op, byte[][] keys, long timeoutAtMillis, byte[] destination, boolean fromLeft, boolean toLeft) {
        this.op = op;
        this.keys = keys;
        this.timeoutAtMillis = timeoutAtMillis;
        this.destination = destination;
        this.fromLeft = fromLeft;
        this.toLeft = toLeft;
    }
}
