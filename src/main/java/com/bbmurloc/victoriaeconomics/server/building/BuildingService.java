package com.bbmurloc.victoriaeconomics.server.building;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import java.util.UUID;
import java.util.Optional;
import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.CommitOutcomeUnknownException;

/** Building identity commands. Creation delegates the necessary joint initialization to an application unit of work. */
public final class BuildingService {
    private final BuildingRegistry buildings;
    private final BuildingRepository repository;
    private final BuildingTypeRegistry types;
    private final BuildingCreation initialize;
    private final Object lock;

    public BuildingService(BuildingRegistry buildings, BuildingRepository repository, BuildingTypeRegistry types,
                           BuildingCreation initialize, Object lock) {
        this.buildings = buildings;
        this.repository = repository;
        this.types = types;
        this.initialize = initialize;
        this.lock = lock;
    }
    public EconomicBuilding createBuilding(String type) {
        throw new IllegalStateException("Explicit registered CompanyId and RegionId are required; no player identity source is available");
    }
    public EconomicBuilding createBuilding(String type, CompanyId owner, RegionId region) {
        return createBuilding(UUID.randomUUID(), type, owner, region);
    }
    public EconomicBuilding createBuilding(UUID id, String type, CompanyId owner, RegionId region) {
        synchronized (lock) {
            if (types.get(type) == null) throw new IllegalArgumentException("Unknown building type: " + type);
            var building = new EconomicBuilding(id, type, owner, region);
            try { initialize.initialize(building); }
            catch (CommitOutcomeUnknownException failure) { throw new BuildingCreationUnconfirmed(building, failure); }
            buildings.add(building);
            return building;
        }
    }
    public Optional<EconomicBuilding> verifyCreation(EconomicBuilding candidate) {
        synchronized (lock) {
            var durable = initialize.verify(candidate);
            durable.ifPresent(root -> { if (buildings.contains(root.getId())) buildings.restore(root); else buildings.add(root); });
            return durable;
        }
    }
    public boolean stopBuilding(UUID id) {
        synchronized (lock) {
            if (!buildings.contains(id)) return false;
            var building = repository.load(id);
            var changed = building.withStatus(BuildingStatus.STOPPED);
            if (changed != building) repository.save(changed, building.identityRevision());
            buildings.restore(changed);
            return true;
        }
    }
}
