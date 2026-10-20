package com.bbmurloc.victoriaeconomics.server.workforce.application;

import com.bbmurloc.victoriaeconomics.common.definition.occupation.OccupationRegistry;
import com.bbmurloc.victoriaeconomics.server.building.BuildingLifecycle;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionRecipeResolver;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionMethodConfigurationRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.SqliteBuildingStaffingRepository;
import java.sql.Connection;
import java.util.UUID;

/** Staged Pending commands only. Does not participate in the live NPC production loop. */
public final class StaffingApplicationService {
    private final Connection connection;
    private final Object lock;
    private final OccupationRegistry occupations;
    private final SqliteBuildingStaffingRepository staffing;
    private final SqliteProductionMethodConfigurationRepository methods;
    private final SqliteBuildingRepository buildings;
    public StaffingApplicationService(Connection connection, ProductionRecipeResolver recipes, OccupationRegistry occupations, Object lock) {
        this.connection = connection; this.lock = lock; this.occupations = occupations;
        staffing = new SqliteBuildingStaffingRepository(connection, recipes, occupations, lock);
        methods = new SqliteProductionMethodConfigurationRepository(connection, recipes, lock);
        buildings = new SqliteBuildingRepository(connection, lock);
    }
    public void requestTarget(UUID building, String role, int target) { pending(building, role, target); }
    public void cancelPending(UUID building, String role) { pending(building, role, null); }
    private void pending(UUID id, String role, Integer target) {
        synchronized (lock) {
            SqliteTransactions.write(connection, tx -> {
                if (occupations.get(role) == null) throw new IllegalArgumentException("Unknown occupation");
                if (buildings.inTransaction(tx).load(id).lifecycle() != BuildingLifecycle.EXISTING)
                    throw new IllegalStateException("Historical building cannot request staffing");
                var repository = staffing.inTransaction(tx);
                var before = repository.load(id);
                var changed = target == null ? before.cancel(role)
                        : before.requestTarget(role, target, methods.inTransaction(tx).load(id).recipe().requiredWorkers());
                if (changed != before) repository.save(changed, before.revision());
                return null;
            });
        }
    }
    public void applyEffectiveChanges(UUID building) {
        throw new IllegalStateException("Effective staffing is unavailable: real payroll accrual, persistent economic time and M3.1 authority switch are required");
    }
}
