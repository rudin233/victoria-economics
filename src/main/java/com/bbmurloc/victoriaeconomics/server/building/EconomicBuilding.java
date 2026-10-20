package com.bbmurloc.victoriaeconomics.server.building;

import java.util.Objects;
import java.util.UUID;
import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;

/** Immutable identity checkpoint; other aggregates remain independently authoritative. */
public final class EconomicBuilding {
    private final UUID id;
    private final String buildingTypeId;
    private final CompanyId ownerCompanyId;
    private final RegionId regionId;
    private final BuildingLifecycle lifecycle;
    private final long identityRevision;
    private final BuildingStatus status;

    public EconomicBuilding(UUID id, String buildingTypeId, CompanyId owner, RegionId region) {
        this(id, buildingTypeId, owner, region, BuildingLifecycle.EXISTING, BuildingStatus.ACTIVE, 0);
    }
    public EconomicBuilding(UUID id, String buildingTypeId, CompanyId owner, RegionId region,
                            BuildingLifecycle lifecycle, BuildingStatus status, long revision) {
        this.id = Objects.requireNonNull(id);
        this.buildingTypeId = Objects.requireNonNull(buildingTypeId);
        if (buildingTypeId.isBlank() || revision < 0) throw new IllegalArgumentException("Invalid building identity");
        this.ownerCompanyId = Objects.requireNonNull(owner);
        this.regionId = Objects.requireNonNull(region);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.status = Objects.requireNonNull(status);
        this.identityRevision = revision;
    }
    public UUID getId() { return id; }
    public String getBuildingTypeId() { return buildingTypeId; }
    public BuildingStatus getStatus() { return status; }
    public CompanyId ownerCompanyId() { return ownerCompanyId; }
    public RegionId regionId() { return regionId; }
    public BuildingLifecycle lifecycle() { return lifecycle; }
    public long identityRevision() { return identityRevision; }
    public EconomicBuilding withStatus(BuildingStatus next) {
        if (lifecycle != BuildingLifecycle.EXISTING) throw new IllegalStateException("Historical identity cannot operate");
        if (next == status) return this;
        return new EconomicBuilding(id, buildingTypeId, ownerCompanyId, regionId, lifecycle, next, Math.incrementExact(identityRevision));
    }
}
