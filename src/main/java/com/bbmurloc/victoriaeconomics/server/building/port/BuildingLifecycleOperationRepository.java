package com.bbmurloc.victoriaeconomics.server.building.port;

import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation;
import java.util.*;

/** Committed facts only. Unknown commits must be verified through a newly trusted repository. */
public interface BuildingLifecycleOperationRepository {
    Optional<BuildingLifecycleOperation> find(UUID operationId);
    Optional<BuildingLifecycleOperation> forBuilding(UUID buildingId);
    List<BuildingLifecycleOperation> unfinished();
    Optional<BuildingLifecycleOperation> verify(BuildingLifecycleOperation.Command command);
}
