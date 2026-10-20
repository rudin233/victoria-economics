package com.bbmurloc.victoriaeconomics.server.building;

/** Stable candidate identity for explicit fresh-connection verification, never a published building. */
public final class BuildingCreationUnconfirmed extends IllegalStateException {
    private final EconomicBuilding candidate;
    public BuildingCreationUnconfirmed(EconomicBuilding candidate, Throwable cause) {
        super("Building creation outcome unknown: " + candidate.getId() + "; verify on a fresh trusted connection before any retry", cause);
        this.candidate = candidate;
    }
    public EconomicBuilding candidate() { return candidate; }
}
