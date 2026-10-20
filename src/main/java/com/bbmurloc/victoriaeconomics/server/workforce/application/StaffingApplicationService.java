package com.bbmurloc.victoriaeconomics.server.workforce.application;

import com.bbmurloc.victoriaeconomics.common.definition.occupation.OccupationRegistry;
import com.bbmurloc.victoriaeconomics.server.production.application.ProductionBoundaryCoordinator;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionRecipeResolver;
import java.sql.Connection;
import java.util.UUID;
import java.util.function.Consumer;

/** Idle changes are payroll-aware atomic transitions. Active Batch requests remain Pending. */
public final class StaffingApplicationService {
    private final ProductionBoundaryCoordinator coordinator;
    private final Consumer<UUID> settleAborted;
    public StaffingApplicationService(Connection c,ProductionRecipeResolver recipes,OccupationRegistry occupations,Object lock){this(new ProductionBoundaryCoordinator(c,recipes,occupations,lock),id->{});}
    public StaffingApplicationService(ProductionBoundaryCoordinator coordinator,Consumer<UUID> settleAborted){this.coordinator=coordinator;this.settleAborted=settleAborted;}
    public void requestTarget(UUID id,String role,int target){if(coordinator.requestStaffing(id,role,target))settleAborted.accept(id);}
    public void cancelPending(UUID id,String role){coordinator.requestStaffing(id,role,null);}
    public void applyEffectiveChanges(UUID id){coordinator.reconfigure(id);}
}
