package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.SqliteBuildingStaffingRepository;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

/** Four aggregate roots and the initial payroll segment share one physical creation transaction. */
public final class ProductionBuildingInitializer implements BuildingCreation {
    private final Connection connection;
    private final Object lock;
    private final SqliteBuildingRepository buildings;
    private final SqliteBuildingStaffingRepository staffing;
    private final Supplier<Connection> verificationConnections;
    private final ProductionMethodConfigurationRepository methods;
    private final ProductionExecutionRepository executions;
    private final ProductionRecipeResolver recipes;

    public ProductionBuildingInitializer(Connection connection, Object lock, SqliteBuildingRepository buildings,
                                        ProductionMethodConfigurationRepository methods, ProductionExecutionRepository executions,
                                        ProductionRecipeResolver recipes, SqliteBuildingStaffingRepository staffing,
                                        Supplier<Connection> verificationConnections) {
        this.connection = connection; this.lock = lock; this.buildings = buildings;
        this.methods = methods; this.executions = executions; this.recipes = recipes;
        this.staffing = staffing; this.verificationConnections = verificationConnections;
    }
    public void initialize(EconomicBuilding building) {
        synchronized (lock) {
            var rules = recipes.rulesFor(building.getBuildingTypeId());
            SqliteTransactions.write(connection, tx -> {
                buildings.inTransaction(tx).create(building);
                methods.create(new ProductionMethodConfiguration(building.getId(), rules, rules.defaults(), null));
                long tick=com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository.inTransaction(connection,tx).acceptedTick();
                executions.create(new ProductionExecution(building.getId(),tick));
                staffing.inTransaction(tx).createEmpty(building.getId());
                var payroll=new com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.SqlitePayrollRepository(connection,lock).inTransaction(tx);
                var currency=payroll.currencyForRegion(building.regionId());
                payroll.enroll(UUID.nameUUIDFromBytes(("payroll:"+building.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),building.getId(),currency);
                return null;
            });
        }
    }
    @Override public Optional<EconomicBuilding> verify(EconomicBuilding candidate) {
        try (var fresh = verificationConnections.get()) {
            if (fresh == connection) throw new IllegalStateException("Fresh verification connection required");
            EconomySchema.validateExisting(fresh);
            var found = SqliteTransactions.read(fresh, tx -> {
                tx.requireConnection(fresh);
                try (var query = fresh.prepareStatement("SELECT b.*,m.building_id AS method_root,e.building_id AS execution_root,s.building_id AS staffing_root,p.id AS payroll_root FROM economic_buildings b LEFT JOIN production_method_configurations m ON m.building_id=b.id LEFT JOIN production_executions e ON e.building_id=b.id LEFT JOIN building_staffing s ON s.building_id=b.id LEFT JOIN payroll_accruals p ON p.building_id=b.id AND p.closed_tick IS NULL WHERE b.id=?")) {
                    query.setString(1, candidate.getId().toString());
                    try (var rows = query.executeQuery()) {
                        if (!rows.next()) return Optional.<EconomicBuilding>empty();
                        if (rows.getString("method_root") == null || rows.getString("execution_root") == null || rows.getString("staffing_root") == null || rows.getString("payroll_root")==null)
                            throw new IllegalStateException("Incomplete jointly created building");
                        return Optional.of(SqliteBuildingRepository.decode(rows));
                    }
                }
            });
            found.ifPresent(root -> {
                if (!root.getBuildingTypeId().equals(candidate.getBuildingTypeId()) || !root.ownerCompanyId().equals(candidate.ownerCompanyId())
                        || !root.regionId().equals(candidate.regionId())) throw new IllegalStateException("Candidate identity mismatch");
            });
            return found; // All four roots and payroll were observed together; never reinsert on absence.
        } catch (SQLException failure) { throw new IllegalStateException("Cannot verify building creation", failure); }
    }
}
