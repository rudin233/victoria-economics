package com.bbmurloc.victoriaeconomics.server.finance.domain;

import java.util.Objects;
import java.util.UUID;

/** Currency identity only. No default world currency or exchange rates. */
public record CurrencyId(UUID value) {
    public CurrencyId { Objects.requireNonNull(value); }
    public static CurrencyId parse(String value) {
        var id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Canonical currency UUID required");
        return new CurrencyId(id);
    }
    @Override public String toString() { return value.toString(); }
}
