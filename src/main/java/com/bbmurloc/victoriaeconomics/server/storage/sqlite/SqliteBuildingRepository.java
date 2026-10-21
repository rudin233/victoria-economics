package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import java.sql.*;
import java.util.*;

/** Identity-only insert/CAS. Detached revisions are published by the service after physical commit. */
public final class SqliteBuildingRepository implements BuildingRepository {
    private final Connection connection;
    private final Object lock;
    public SqliteBuildingRepository(Connection connection, Object lock) { this.connection = connection; this.lock = lock; }

    @Override public void create(EconomicBuilding building) {
        synchronized (lock) { SqliteTransactions.write(connection, tx -> { inTransaction(tx).create(building); return null; }); }
    }
    @Override public void save(EconomicBuilding building, long expected) {
        synchronized (lock) { SqliteTransactions.write(connection, tx -> { inTransaction(tx).save(building, expected); return null; }); }
    }
    @Override public EconomicBuilding load(UUID id) {
        synchronized (lock) { SqliteTransactions.requireCommittedReads(connection); return read(id); }
    }
    public EconomicBuilding inSnapshot(SqliteTransactions.ReadSession tx, UUID id) { tx.requireConnection(connection); return read(id); }
    @Override public List<EconomicBuilding> loadAll() {
        synchronized (lock) {
            SqliteTransactions.requireCommittedReads(connection);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM economic_buildings")) {
                var result = new ArrayList<EconomicBuilding>();
                while (rows.next()) result.add(decode(rows));
                return List.copyOf(result);
            } catch (SQLException failure) { throw new IllegalStateException("Cannot read building directory", failure); }
        }
    }
    public Session inTransaction(SqliteTransactions.WriteSession tx) {
        tx.requireConnection(connection);
        return new Session() {
            @Override public EconomicBuilding load(UUID id) {
                try { return tx.perform(() -> read(id)); } catch (SQLException failure) { throw failure(failure); }
            }
            @Override public void create(EconomicBuilding building) {
                try { tx.perform(() -> {
                    if (building.identityRevision() != 0 || building.lifecycle() != BuildingLifecycle.EXISTING)
                        throw new IllegalArgumentException("New identity must be existing at revision zero");
                    try (var statement = connection.prepareStatement("INSERT INTO economic_buildings(id,building_type_id,status,owner_company_id,region_id,lifecycle,identity_revision) VALUES(?,?,?,?,?,?,?)")) {
                        statement.setString(1, building.getId().toString()); statement.setString(2, building.getBuildingTypeId());
                        statement.setString(3, building.getStatus().name()); statement.setString(4, building.ownerCompanyId().toString());
                        statement.setString(5, building.regionId().toString()); statement.setString(6, building.lifecycle().name());
                        statement.setLong(7, building.identityRevision()); statement.executeUpdate();
                    }
                    return null;
                }); } catch (SQLException failure) { throw failure(failure); }
            }
            @Override public void save(EconomicBuilding building, long expected) {
                try { tx.perform(() -> {
                    if (building.identityRevision() != Math.incrementExact(expected)) throw new IllegalArgumentException("Save one identity revision");
                    var current = read(building.getId());
                    com.bbmurloc.victoriaeconomics.server.building.infrastructure.SqliteBuildingLifecycleOperationRepository.requireOperational(connection,tx,building.getId());
                    if (current.lifecycle() != BuildingLifecycle.EXISTING) throw new IllegalStateException("Historical identity is immutable");
                    if (!current.getBuildingTypeId().equals(building.getBuildingTypeId())
                            || !current.ownerCompanyId().equals(building.ownerCompanyId()) || !current.regionId().equals(building.regionId())
                            || current.lifecycle() != building.lifecycle())
                        throw new IllegalStateException("Ownership/region/lifecycle changes require future controlled closeout");
                    try (var statement = connection.prepareStatement("UPDATE economic_buildings SET status=?, identity_revision=? WHERE id=? AND identity_revision=?")) {
                        statement.setString(1, building.getStatus().name()); statement.setLong(2, building.identityRevision());
                        statement.setString(3, building.getId().toString()); statement.setLong(4, expected);
                        if (statement.executeUpdate() != 1) throw new IllegalStateException("Building identity revision conflict");
                    }
                    return null;
                }); } catch (SQLException failure) { throw failure(failure); }
            }
        };
    }
    private EconomicBuilding read(UUID id) {
        try (var statement = connection.prepareStatement("SELECT * FROM economic_buildings WHERE id=?")) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Unknown building: " + id);
                return decode(rows);
            }
        } catch (SQLException failure) { throw failure(failure); }
    }
    public static EconomicBuilding decode(ResultSet row) throws SQLException {
        Object revision = row.getObject("identity_revision");
        if (!(revision instanceof Long || revision instanceof Integer))
            throw new IllegalStateException("Invalid identity revision type");
        String text = row.getString("id");
        UUID id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw new IllegalStateException("Canonical building UUID required");
        return new EconomicBuilding(id, row.getString("building_type_id"),
                CompanyId.parse(row.getString("owner_company_id")), RegionId.parse(row.getString("region_id")),
                BuildingLifecycle.valueOf(row.getString("lifecycle")), BuildingStatus.valueOf(row.getString("status")),
                row.getLong("identity_revision"));
    }
    private static IllegalStateException failure(SQLException failure) { return new IllegalStateException("Building persistence failed", failure); }
    @Override public void delete(UUID id) { throw new UnsupportedOperationException("Historical building identities cannot be physically deleted"); }
}
