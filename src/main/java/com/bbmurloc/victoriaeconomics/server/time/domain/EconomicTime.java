package com.bbmurloc.victoriaeconomics.server.time.domain;

/** Neither accepted time nor callback return is a business completion receipt. Never replay uncertain production. */
public record EconomicTime(long acceptedTick, long payrollTick, long productionCallbackReturnedTick,
                           Long firstUncertainProductionTick, long revision) {
    public static final long TICKS_PER_MINUTE = 1200;
    public EconomicTime {
        if (acceptedTick < 0 || payrollTick < 0 || payrollTick > acceptedTick || productionCallbackReturnedTick < 0
                || productionCallbackReturnedTick > acceptedTick || revision < 0
                || (firstUncertainProductionTick != null && (firstUncertainProductionTick <= 0 || firstUncertainProductionTick > acceptedTick)))
            throw new IllegalArgumentException("Invalid persisted economic time");
    }
    public long periodId() { return acceptedTick / TICKS_PER_MINUTE; }
    public long lastPeriodBoundary() { return Math.multiplyExact(periodId(), TICKS_PER_MINUTE); }
    public boolean payrollCaughtUp() { return payrollTick == acceptedTick; }
    public boolean hasUnconfirmedProductionCallback() { return firstUncertainProductionTick != null || productionCallbackReturnedTick < acceptedTick; }
}
