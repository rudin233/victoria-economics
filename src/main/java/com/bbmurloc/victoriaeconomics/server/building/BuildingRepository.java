package com.bbmurloc.victoriaeconomics.server.building;

import java.util.List;
import java.util.UUID;

public interface BuildingRepository {

    void create(EconomicBuilding building);
    void save(EconomicBuilding building, long expectedRevision);
    EconomicBuilding load(UUID id);

    void delete(UUID buildingId);

    List<EconomicBuilding> loadAll();

    /** Transaction-local detached identity; caller must confirm the outer commit before publishing. */
    interface Session {
        void create(EconomicBuilding building);
        EconomicBuilding load(UUID id);
        void save(EconomicBuilding building, long expectedRevision);
    }
}
