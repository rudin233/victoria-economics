package com.bbmurloc.victoriaeconomics.server.workforce.infrastructure;

import com.bbmurloc.victoriaeconomics.common.definition.occupation.OccupationRegistry;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import com.bbmurloc.victoriaeconomics.server.workforce.domain.RegionalLaborSupply;
import com.bbmurloc.victoriaeconomics.server.workforce.port.RegionalLaborSupplyRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteTransactions;
import java.sql.*;
import java.util.*;

public final class SqliteRegionalLaborSupplyRepository implements RegionalLaborSupplyRepository {
    private final Connection connection;
    private final OccupationRegistry occupations;
    private final Object lock;
    public SqliteRegionalLaborSupplyRepository(Connection connection, OccupationRegistry occupations, Object lock) {
        this.connection = connection; this.occupations = occupations; this.lock = lock;
    }
    @Override public Optional<RegionalLaborSupply> find(RegionId region, String role) {
        synchronized (lock) { SqliteTransactions.requireCommittedReads(connection); return read(region, role); }
    }
    @Override public long occupied(RegionId region, String role) {
        synchronized (lock) { SqliteTransactions.requireCommittedReads(connection); return sum(region, role); }
    }
    public Session inTransaction(SqliteTransactions.WriteSession tx) {
        tx.requireConnection(connection);
        return new Session() {
            @Override public Optional<RegionalLaborSupply> find(RegionId region, String role) {
                try { return tx.perform(() -> read(region, role)); }
                catch (SQLException failure) { throw new IllegalStateException("Cannot read supply", failure); }
            }
            @Override public long occupied(RegionId region, String role) {
                try { return tx.perform(() -> sum(region, role)); }
                catch (SQLException failure) { throw new IllegalStateException("Cannot aggregate employment", failure); }
            }
            @Override public void create(RegionalLaborSupply supply) { persist(supply, null); }
            @Override public void save(RegionalLaborSupply supply, long expected) { persist(supply, expected); }
            private void persist(RegionalLaborSupply supply, Long expected) {
                try { tx.perform(() -> {
                    if (occupations.get(supply.occupationId()) == null) throw new IllegalArgumentException("Unknown occupation");
                    if (supply.totalSupply() < sum(supply.regionId(), supply.occupationId())) throw new IllegalStateException("Supply cannot fall below actual employment");
                    if (supply.revision() != (expected == null ? 0 : Math.incrementExact(expected))) throw new IllegalArgumentException("Invalid supply revision");
                    String sql = expected == null
                            ? "INSERT INTO regional_labor_supply(total_supply,revision,region_id,occupation_id) VALUES(?,?,?,?)"
                            : "UPDATE regional_labor_supply SET total_supply=?,revision=? WHERE region_id=? AND occupation_id=? AND revision=?";
                    try (var statement = connection.prepareStatement(sql)) {
                        statement.setLong(1, supply.totalSupply()); statement.setLong(2, supply.revision());
                        statement.setString(3, supply.regionId().toString()); statement.setString(4, supply.occupationId());
                        if (expected != null) statement.setLong(5, expected);
                        if (statement.executeUpdate() != 1) throw new IllegalStateException("Supply revision conflict");
                    }
                    return null;
                }); } catch (SQLException failure) { throw new IllegalStateException("Supply persistence failed", failure); }
            }
        };
    }
    public Optional<RegionalLaborSupply> inSnapshot(SqliteTransactions.ReadSession tx, RegionId region, String role) {
        tx.requireConnection(connection); return read(region, role);
    }
    public long occupiedInSnapshot(SqliteTransactions.ReadSession tx, RegionId region, String role) {
        tx.requireConnection(connection); return sum(region, role);
    }
    private Optional<RegionalLaborSupply> read(RegionId region, String role) {
        try (var statement = connection.prepareStatement("SELECT total_supply,revision FROM regional_labor_supply WHERE region_id=? AND occupation_id=?")) {
            statement.setString(1, region.toString()); statement.setString(2, role);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new RegionalLaborSupply(region, role, rows.getLong(1), rows.getLong(2))) : Optional.empty();
            }
        } catch (SQLException failure) { throw new IllegalStateException("Cannot read supply", failure); }
    }
    private long sum(RegionId region, String role) {
        try (var statement = connection.prepareStatement("SELECT s.employed FROM building_staffing_roles s JOIN economic_buildings b ON b.id=s.building_id WHERE b.region_id=? AND s.occupation_id=?")) {
            statement.setString(1, region.toString()); statement.setString(2, role);
            try (var rows = statement.executeQuery()) {
                long result = 0;
                while (rows.next()) result = Math.addExact(result, rows.getLong(1));
                return result;
            }
        } catch (SQLException failure) { throw new IllegalStateException("Cannot aggregate actual employment", failure); }
    }
}
