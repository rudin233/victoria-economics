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
    private final Runnable productionRecovery;
    private final Runnable paymentRecovery;
    private boolean halted;
    private long ticks;

    /** Isolated simulations/legacy regression fixtures only. The server always requires the persistent constructor. */
    public EconomicClock(Runnable tick) {
        this.tick = Objects.requireNonNull(tick);
        this.repository = null; this.payrollRecovery = null;this.productionRecovery=()->{};this.paymentRecovery=()->{};
    }

    /** Time/payroll-only fixtures; formal server wiring also supplies per-building production recovery. */
    public EconomicClock(EconomicTimeRepository repository, Runnable payrollRecovery, Runnable tick) {
        this(repository,payrollRecovery,tick,()->{});
    }
    /** Production-only regression fixtures; the formal constructor also supplies payment recovery. */
    public EconomicClock(EconomicTimeRepository repository,Runnable payrollRecovery,Runnable tick,Runnable productionRecovery){
        this(repository,payrollRecovery,tick,productionRecovery,()->{});
    }
    /** Formal server wiring includes company-period recovery between accrual and production. */
    public EconomicClock(EconomicTimeRepository repository,Runnable payrollRecovery,Runnable tick,Runnable productionRecovery,Runnable paymentRecovery){
        this.repository = Objects.requireNonNull(repository); this.payrollRecovery = Objects.requireNonNull(payrollRecovery);
        this.tick = Objects.requireNonNull(tick);
        this.productionRecovery=Objects.requireNonNull(productionRecovery);
        this.paymentRecovery=Objects.requireNonNull(paymentRecovery);
        repository.recordRecoveryGap(); payrollRecovery.run();
        paymentRecovery.run();
        productionRecovery.run();
    }

    public void onServerTick() {
        if (repository == null) { tick.run(); ticks = Math.incrementExact(ticks); return; }
        if (halted) throw new IllegalStateException("Economic clock halted after failure; reopen a trusted connection and verify persisted cursors");
        try {
            paymentRecovery.run();
            productionRecovery.run();
            var accepted = repository.acceptNext(repository.load().acceptedTick());
            payrollRecovery.run();
            paymentRecovery.run();
            tick.run();
            // Formal production already verified every building and confirmed this Tick in SQLite.
            // This idempotent invocation receipt alone is never evidence of business completion.
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
