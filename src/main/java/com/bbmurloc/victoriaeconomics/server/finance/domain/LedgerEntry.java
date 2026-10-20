package com.bbmurloc.victoriaeconomics.server.finance.domain;

import java.util.UUID;

public record LedgerEntry(String eventId, int line, UUID accountId, long delta, long balanceAfter) {}
