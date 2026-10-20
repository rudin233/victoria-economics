package com.bbmurloc.victoriaeconomics.server.payroll.domain;

import java.util.UUID;

/** Debtor, beneficiary and currency are permanently supplied by the referenced historical segment. */
public record PayrollLiability(UUID segmentId, long periodId, long amount, long paid) {
    public PayrollLiability {
        if (segmentId == null || periodId <= 0 || amount < 0 || paid < 0 || paid > amount) throw new IllegalArgumentException("Invalid payroll liability");
    }
    public long unpaid() { return Math.subtractExact(amount, paid); }
}
