package com.bbmurloc.victoriaeconomics.server.payroll.domain;

import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import com.bbmurloc.victoriaeconomics.server.finance.domain.CurrencyId;
import java.math.BigInteger;
import java.util.*;

/** Independent wage segment. Never reconstruct historical debtor from the current Building owner. */
public record PayrollAccrual(UUID id, UUID buildingId, CompanyId company, RegionId region, CurrencyId currency,
                             long startTick, long lastAccruedTick, Long closedTick, long employees,
                             long accrued, long unmatured, long matured, long paid, long remainder, long revision) {
    public PayrollAccrual {
        Objects.requireNonNull(id); Objects.requireNonNull(buildingId); Objects.requireNonNull(company); Objects.requireNonNull(region); Objects.requireNonNull(currency);
        if (startTick < 0 || lastAccruedTick < startTick || (closedTick != null && (closedTick < startTick || closedTick > lastAccruedTick))
                || employees < 0 || accrued < 0 || unmatured < 0 || matured < 0 || paid < 0 || paid > matured
                || Math.addExact(unmatured, matured) != accrued || remainder < 0 || remainder >= 1200 || revision < 0)
            throw new IllegalArgumentException("Invalid payroll accrual");
    }
    public static PayrollAccrual start(UUID id, UUID building, CompanyId company, RegionId region, CurrencyId currency, long tick, long employees) {
        return new PayrollAccrual(id, building, company, region, currency, tick, tick, null, employees, 0, 0, 0, 0, 0, 0);
    }
    public long unpaid() { return Math.subtractExact(matured, paid); }
    public record Result(PayrollAccrual accrual, List<PayrollLiability> liabilities) { public Result { liabilities = List.copyOf(liabilities); } }
    /** Caller supplies immutable rate history from the same authoritative transaction. [from,to) intervals. */
    public Result accrueThrough(long target, List<RegionalWageRate> history) {
        if (target < lastAccruedTick) throw new IllegalArgumentException("Payroll cursor cannot move backwards");
        if (target == lastAccruedTick) return new Result(this, List.of());
        var rates = history.stream().sorted(Comparator.comparingLong(RegionalWageRate::effectiveTick)).toList();
        long from = lastAccruedTick, total = accrued, pending = unmatured, due = matured, rem = remainder;
        var liabilities = new ArrayList<PayrollLiability>();
        while (from < target) {
            long boundary = nextBoundary(from, target), to = boundary;
            long rate = 0;
            if (closedTick == null || from < closedTick) {
                RegionalWageRate current = null;
                for (var candidate : rates) {
                    if (!candidate.region().equals(region) || !candidate.currency().equals(currency)) throw new IllegalArgumentException("Wage history identity mismatch");
                    if (candidate.effectiveTick() <= from) current = candidate;
                    else { to = Math.min(to, candidate.effectiveTick()); break; }
                }
                if (current == null) throw new IllegalStateException("Regional wage rate unavailable at tick " + from);
                rate = current.amount();
                if (closedTick != null) to = Math.min(to, closedTick);
            }
            // BigInteger preserves the exact numerator even if intermediate n*w*dt exceeds long.
            var raw = BigInteger.valueOf(employees).multiply(BigInteger.valueOf(rate)).multiply(BigInteger.valueOf(Math.subtractExact(to, from))).add(BigInteger.valueOf(rem));
            var parts = raw.divideAndRemainder(BigInteger.valueOf(1200));
            long added = parts[0].longValueExact(); rem = parts[1].longValueExact();
            total = Math.addExact(total, added); pending = Math.addExact(pending, added);
            if (to % 1200 == 0) {
                liabilities.add(new PayrollLiability(id, to / 1200, pending, 0));
                due = Math.addExact(due, pending); pending = 0;
            }
            from = to;
        }
        return new Result(new PayrollAccrual(id, buildingId, company, region, currency, startTick, target, closedTick, employees,
                total, pending, due, paid, rem, Math.incrementExact(revision)), liabilities);
    }
    private static long nextBoundary(long from, long target) {
        long distance = 1200 - from % 1200;
        return target - from < distance ? target : Math.addExact(from, distance);
    }
    /** Future shared-UoW capability only: accrue old headcount first, then change the base. */
    public PayrollAccrual withEmployeesAtCursor(long count, long tick) {
        if (closedTick != null || tick != lastAccruedTick || count < 0) throw new IllegalArgumentException("Accrue old employees through the change tick first");
        return new PayrollAccrual(id, buildingId, company, region, currency, startTick, lastAccruedTick, null, count,
                accrued, unmatured, matured, paid, remainder, Math.incrementExact(revision));
    }
    public PayrollAccrual closeAtCursor(long tick) {
        if (closedTick != null || tick != lastAccruedTick) throw new IllegalArgumentException("Accrue historical segment through close tick first");
        return new PayrollAccrual(id, buildingId, company, region, currency, startTick, lastAccruedTick, tick, employees,
                accrued, unmatured, matured, paid, remainder, Math.incrementExact(revision));
    }
}
