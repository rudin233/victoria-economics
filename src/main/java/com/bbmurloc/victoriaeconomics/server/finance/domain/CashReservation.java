package com.bbmurloc.victoriaeconomics.server.finance.domain;

import java.util.Objects;
import java.util.UUID;

public record CashReservation(UUID id, UUID accountId, long amount, String purpose, boolean active) {
    public CashReservation {
        Objects.requireNonNull(id); Objects.requireNonNull(accountId);
        if (amount <= 0 || purpose == null || purpose.isBlank()) throw new IllegalArgumentException("Positive reservation and explicit purpose required");
    }
}
