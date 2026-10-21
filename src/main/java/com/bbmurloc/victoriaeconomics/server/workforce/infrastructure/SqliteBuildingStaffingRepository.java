package com.bbmurloc.victoriaeconomics.server.workforce.infrastructure;

import com.bbmurloc.victoriaeconomics.common.definition.occupation.OccupationRegistry;
import com.bbmurloc.victoriaeconomics.server.workforce.domain.BuildingStaffing;
import com.bbmurloc.victoriaeconomics.server.workforce.port.BuildingStaffingRepository;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionRecipeResolver;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionMethodConfigurationRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import java.sql.*;
import java.util.*;

/** Storage foundation only; no standalone effective-employment mutation entry point. */
public final class SqliteBuildingStaffingRepository implements BuildingStaffingRepository {
    private final Connection connection;
    private final Object lock;
    private final OccupationRegistry occupations;
    private final SqliteBuildingRepository buildings;
    private final SqliteProductionMethodConfigurationRepository methods;
    private final SqliteRegionalLaborSupplyRepository supply;
    public SqliteBuildingStaffingRepository(Connection connection, ProductionRecipeResolver recipes, OccupationRegistry occupations, Object lock) {
        this.connection = connection; this.lock = lock; this.occupations = occupations;
        buildings = new SqliteBuildingRepository(connection, lock);
        methods = new SqliteProductionMethodConfigurationRepository(connection, recipes, lock);
        supply = new SqliteRegionalLaborSupplyRepository(connection, occupations, lock);
    }
    @Override public BuildingStaffing load(UUID id) {
        synchronized (lock) { return SqliteTransactions.read(connection, tx -> inSnapshot(tx, id)); }
    }
    public BuildingStaffing inSnapshot(SqliteTransactions.ReadSession tx, UUID id) { tx.requireConnection(connection); return read(id); }
    public Session inTransaction(SqliteTransactions.WriteSession tx) {
        tx.requireConnection(connection);
        return new Session() {
            @Override public BuildingStaffing load(UUID id) {
                try { return tx.perform(() -> read(id)); }
                catch (SQLException failure) { throw new IllegalStateException("Cannot read staffing", failure); }
            }
            @Override public void createEmpty(UUID id) {
                try { tx.perform(() -> {
                    try (var statement = connection.prepareStatement("INSERT INTO building_staffing(building_id,revision) VALUES(?,0)")) {
                        statement.setString(1, id.toString()); statement.executeUpdate();
                    }
                    return null;
                }); } catch (SQLException failure) { throw new IllegalStateException("Cannot initialize staffing", failure); }
            }
            @Override public void save(BuildingStaffing changed, long expected) {
                try { tx.perform(() -> {
                    if (changed.revision() != Math.incrementExact(expected)) throw new IllegalArgumentException("Save one staffing revision");
                    var prior = read(changed.buildingId());
                    com.bbmurloc.victoriaeconomics.server.building.infrastructure.SqliteBuildingLifecycleOperationRepository.requireOperational(connection,tx,changed.buildingId());
                    if (prior.revision() != expected) throw new IllegalStateException("Staffing revision conflict");
                    var building = buildings.inTransaction(tx).load(changed.buildingId());
                    if (building.lifecycle() != com.bbmurloc.victoriaeconomics.server.building.BuildingLifecycle.EXISTING)
                        throw new IllegalStateException("Historical staffing changes require controlled closeout");
                    var capacity = methods.inTransaction(tx).load(changed.buildingId()).recipe().requiredWorkers();
                    var roles = new HashSet<>(changed.employed().keySet()); roles.addAll(changed.pending().keySet());
                    for (String role : roles) {
                        if (occupations.get(role) == null) throw new IllegalArgumentException("Unknown occupation");
                        int count = changed.employed().getOrDefault(role, 0);
                        if (count > capacity.getOrDefault(role, 0) || changed.pending().getOrDefault(role, 0) > capacity.getOrDefault(role, 0))
                            throw new IllegalArgumentException("Staffing exceeds effective PM capacity");
                        long others = Math.subtractExact(supply.inTransaction(tx).occupied(building.regionId(), role), prior.employed().getOrDefault(role, 0));
                        long total = Math.addExact(others, count);
                        long limit = supply.inTransaction(tx).find(building.regionId(), role).map(s -> s.totalSupply()).orElse(0L);
                        if (total > limit) throw new IllegalStateException("Regional labor supply exhausted");
                    }
                    try (var statement = connection.prepareStatement("UPDATE building_staffing SET revision=? WHERE building_id=? AND revision=?")) {
                        statement.setLong(1, changed.revision()); statement.setString(2, changed.buildingId().toString()); statement.setLong(3, expected);
                        if (statement.executeUpdate() != 1) throw new IllegalStateException("Staffing revision conflict");
                    }
                    try (var statement = connection.prepareStatement("DELETE FROM building_staffing_roles WHERE building_id=?")) {
                        statement.setString(1, changed.buildingId().toString()); statement.executeUpdate();
                    }
                    try (var statement = connection.prepareStatement("INSERT INTO building_staffing_roles(building_id,occupation_id,employed,pending_target) VALUES(?,?,?,?)")) {
                        for (String role : roles) {
                            statement.setString(1, changed.buildingId().toString()); statement.setString(2, role);
                            statement.setInt(3, changed.employed().getOrDefault(role, 0));
                            if (changed.pending().containsKey(role)) statement.setInt(4, changed.pending().get(role)); else statement.setNull(4, Types.INTEGER);
                            statement.executeUpdate();
                        }
                    }
                    return null;
                }); } catch (SQLException failure) { throw new IllegalStateException("Staffing persistence failed", failure); }
            }
        };
    }
    private BuildingStaffing read(UUID id) {
        try (var header = connection.prepareStatement("SELECT revision FROM building_staffing WHERE building_id=?")) {
            header.setString(1, id.toString());
            long revision;
            try (var rows = header.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Staffing missing: " + id);
                revision = rows.getLong(1);
            }
            var employed = new TreeMap<String, Integer>(); var pending = new TreeMap<String, Integer>();
            try (var statement = connection.prepareStatement("SELECT occupation_id,employed,pending_target FROM building_staffing_roles WHERE building_id=?")) {
                statement.setString(1, id.toString());
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        employed.put(rows.getString(1), Math.toIntExact(rows.getLong(2)));
                        long target = rows.getLong(3);
                        if (!rows.wasNull()) pending.put(rows.getString(1), Math.toIntExact(target));
                    }
                }
            }
            return new BuildingStaffing(id, employed, pending, revision);
        } catch (SQLException failure) { throw new IllegalStateException("Cannot read staffing", failure); }
    }
}
