package com.bbmurloc.victoriaeconomics.server.production.application;

import java.util.Objects;
import com.bbmurloc.victoriaeconomics.server.time.port.EconomicTimeRepository;
import com.bbmurloc.victoriaeconomics.server.time.domain.EconomicTime;

/**
 * Counts only executed logical server ticks. No wall-time/offline or chunk-load dependency.
 */
public final class EconomicClock {
    public static final int TICKS_PER_MINUTE = 1200;
    private final Runnable tick;
    private final EconomicTimeRepository repository;
    private final Runnable payrollRecovery;
    private boolean halted;
    private long ticks;

    /** Isolated simulations/legacy regression fixtures only. The server always requires the persistent constructor. */
    public EconomicClock(Runnable tick) {
        this.tick = Objects.requireNonNull(tick);
        this.repository = null; this.payrollRecovery = null;
    }

    /** Formal world clock. Construction recovers wages, but NEVER replays an earlier production callback. */
    public EconomicClock(EconomicTimeRepository repository, Runnable payrollRecovery, Runnable tick) {
        this.repository = Objects.requireNonNull(repository); this.payrollRecovery = Objects.requireNonNull(payrollRecovery);
        this.tick = Objects.requireNonNull(tick);
        repository.recordRecoveryGap(); payrollRecovery.run();
    }

    public void onServerTick() {
        if (repository == null) { tick.run(); ticks = Math.incrementExact(ticks); return; }
        if (halted) throw new IllegalStateException("Economic clock halted after failure; reopen a trusted connection and verify persisted cursors");
        try {
            var accepted = repository.acceptNext(repository.load().acceptedTick());
            payrollRecovery.run();
            tick.run();
            // The legacy coordinator catches per-building failures. This acknowledges invocation return ONLY;
            // StartIntent, settlement and boundary recovery remain independent, not globally completed here.
            repository.acknowledgeProductionCallback(accepted.acceptedTick());
        } catch (RuntimeException | Error failure) { halted = true; throw failure; }
    }

    public long elapsedTicks() {
        return repository == null ? ticks : repository.load().acceptedTick();
    }

    public EconomicTime persistentTime() {
        if (repository == null) throw new IllegalStateException("Isolated in-memory test clock has no persisted authority");
        return repository.load();
    }
}
