package com.bbmurloc.victoriaeconomics.server.region.domain;

import java.util.Objects;

/** Identity only; does not invent population, wages or supply. */
public record Region(RegionId id) {
    public Region { Objects.requireNonNull(id); }
}
