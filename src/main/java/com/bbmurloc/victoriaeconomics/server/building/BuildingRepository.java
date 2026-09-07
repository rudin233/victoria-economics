package com.bbmurloc.victoriaeconomics.server.building;

import java.util.List;
import java.util.UUID;

public interface BuildingRepository {

    void save(EconomicBuilding building);

    void delete(UUID buildingId);

    List<EconomicBuilding> loadAll();
}