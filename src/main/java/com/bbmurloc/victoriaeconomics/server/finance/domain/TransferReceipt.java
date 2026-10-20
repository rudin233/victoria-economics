package com.bbmurloc.victoriaeconomics.server.finance.domain;

import java.util.Objects;
import java.util.UUID;

/** Full command identity, used both for execution and durable verification. */
public record TransferReceipt(UUID id, UUID source, UUID destination, CurrencyId currency, long amount) {
    public TransferReceipt {
        Objects.requireNonNull(id); Objects.requireNonNull(source); Objects.requireNonNull(destination); Objects.requireNonNull(currency);
        if (source.equals(destination) || amount <= 0) throw new IllegalArgumentException("Positive transfer between distinct accounts required");
    }
}
