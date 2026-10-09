package com.bbmurloc.victoriaeconomics.server.production.application;

import java.util.Objects;

/**
 * Counts only executed logical server ticks. No wall-time/offline or chunk-load dependency.
 */
public final class EconomicClock {
    public static final int TICKS_PER_MINUTE = 1200;
    private final Runnable tick;
    private long ticks;

    public EconomicClock(Runnable tick) {
        this.tick = Objects.requireNonNull(tick);
    }

    public void onServerTick() {
        tick.run();
        ticks++;
    }

    public long elapsedTicks() {
        return ticks;
    }
}
