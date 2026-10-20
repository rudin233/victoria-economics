package com.bbmurloc.victoriaeconomics.server.region.domain;

import java.util.*;

public record RegionId(UUID value) {
    public RegionId { Objects.requireNonNull(value); }
    public static RegionId parse(String value) {
        var id = new RegionId(UUID.fromString(value));
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Canonical region UUID required");
        return id;
    }
    @Override public String toString() { return value.toString(); }
}
