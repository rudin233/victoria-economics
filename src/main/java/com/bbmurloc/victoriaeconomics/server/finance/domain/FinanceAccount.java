package com.bbmurloc.victoriaeconomics.server.finance.domain;

import java.util.Objects;
import java.util.UUID;

/** Balance is authoritative; reserved is a derived snapshot of active reservation rows. */
public record FinanceAccount(UUID id, OwnerKind ownerKind, UUID ownerId, CurrencyId currency,
                             long balance, long reserved, long revision) {
    public enum OwnerKind { COMPANY, REGION }
    public FinanceAccount {
        Objects.requireNonNull(id); Objects.requireNonNull(ownerKind); Objects.requireNonNull(ownerId); Objects.requireNonNull(currency);
        if (balance < 0 || reserved < 0 || reserved > balance || revision < 0) throw new IllegalArgumentException("Invalid account cash");
    }
    public long available() { return Math.subtractExact(balance, reserved); }
}
