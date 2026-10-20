package com.bbmurloc.victoriaeconomics.server.company.domain;

import java.util.*;

public record CompanyId(UUID value) {
    public CompanyId { Objects.requireNonNull(value); }
    public static CompanyId parse(String value) {
        var id = new CompanyId(UUID.fromString(value));
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Canonical company UUID required");
        return id;
    }
    @Override public String toString() { return value.toString(); }
}
