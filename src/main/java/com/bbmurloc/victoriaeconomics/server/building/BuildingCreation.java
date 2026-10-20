package com.bbmurloc.victoriaeconomics.server.building;

import java.util.Optional;

public interface BuildingCreation {
    void initialize(EconomicBuilding building);
    /** Must use a fresh trusted connection; absence is verified without retrying creation. */
    Optional<EconomicBuilding> verify(EconomicBuilding candidate);
}
