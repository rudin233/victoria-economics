package com.bbmurloc.victoriaeconomics.server.workforce.infrastructure;

import com.bbmurloc.victoriaeconomics.common.definition.occupation.OccupationRegistry;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionRecipeResolver;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionMethodConfigurationRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.workforce.port.StaffingQueryPort;
import java.sql.Connection;
import java.util.*;

/** One DEFERRED committed snapshot for the staged model; never consumes NPC employment. */
public final class SqliteStaffingQuery implements StaffingQueryPort {
    private final Connection connection;
    private final Object lock;
    private final SqliteBuildingRepository buildings;
    private final SqliteBuildingStaffingRepository staffing;
    private final SqliteProductionMethodConfigurationRepository methods;
    private final SqliteRegionalLaborSupplyRepository supply;
    public SqliteStaffingQuery(Connection connection, ProductionRecipeResolver recipes, OccupationRegistry occupations, Object lock) {
        this.connection = connection; this.lock = lock;
        buildings = new SqliteBuildingRepository(connection, lock);
        staffing = new SqliteBuildingStaffingRepository(connection, recipes, occupations, lock);
        methods = new SqliteProductionMethodConfigurationRepository(connection, recipes, lock);
        supply = new SqliteRegionalLaborSupplyRepository(connection, occupations, lock);
    }
    @Override public Facts read(UUID id) {
        synchronized (lock) {
            return SqliteTransactions.read(connection, tx -> {
                var building = buildings.inSnapshot(tx, id);
                var root = staffing.inSnapshot(tx, id);
                var pm = methods.inSnapshot(tx, id);
                var capacity = pm.recipe().requiredWorkers();
                var roles = new TreeSet<>(capacity.keySet()); roles.addAll(root.employed().keySet()); roles.addAll(root.pending().keySet());
                var facts = new TreeMap<String, Supply>();
                for (String role : roles) {
                    var total = supply.inSnapshot(tx, building.regionId(), role);
                    facts.put(role, new Supply(total.isPresent() ? OptionalLong.of(total.get().totalSupply()) : OptionalLong.empty(),
                            supply.occupiedInSnapshot(tx, building.regionId(), role)));
                }
                return new Facts(root, capacity, pm.effectiveRevision(), facts);
            });
        }
    }
}
