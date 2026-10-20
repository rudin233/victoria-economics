package com.bbmurloc.victoriaeconomics.server.payroll.domain;

import com.bbmurloc.victoriaeconomics.server.finance.domain.CurrencyId;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import java.util.Objects;

/** Region average, smallest currency units per person per 1200 ticks. No occupation multiplier. */
public record RegionalWageRate(RegionId region, CurrencyId currency, long effectiveTick, long amount, long revision) {
    public RegionalWageRate {
        Objects.requireNonNull(region); Objects.requireNonNull(currency);
        if (effectiveTick < 0 || amount < 0 || revision < 0) throw new IllegalArgumentException("Invalid wage rate");
    }
}
