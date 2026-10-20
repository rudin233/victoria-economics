package com.bbmurloc.victoriaeconomics.server.company.domain;

import java.util.Objects;

/** Identity only; no player assignment, account or initial cash. */
public record Company(CompanyId id) {
    public Company { Objects.requireNonNull(id); }
}
