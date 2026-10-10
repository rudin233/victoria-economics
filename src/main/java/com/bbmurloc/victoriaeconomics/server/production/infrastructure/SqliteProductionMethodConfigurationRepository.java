package com.bbmurloc.victoriaeconomics.server.production.infrastructure;

import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteTransactions;
import com.google.gson.Gson;
import java.sql.*;
import java.util.*;

public final class SqliteProductionMethodConfigurationRepository implements ProductionMethodConfigurationRepository {
    private final Connection connection;
    private final Object lock;
    private final ProductionRecipeResolver recipes;
    private final Gson gson = new Gson();
    private record Selections(Map<String, String> methods) {}

    public SqliteProductionMethodConfigurationRepository(Connection connection, ProductionRecipeResolver recipes, Object lock) {
        this.connection = connection;
        this.recipes = recipes;
        this.lock = lock;
    }

    @Override
    public ProductionMethodConfiguration load(UUID id) {
        synchronized (lock) {
            SqliteTransactions.requireCommittedReads(connection);
            try (var statement = connection.prepareStatement("SELECT c.*, b.building_type_id FROM production_method_configurations c JOIN economic_buildings b ON b.id=c.building_id WHERE c.building_id=?")) {
                statement.setString(1, id.toString());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Production configuration missing: " + id);
                    String pending = rows.getString("pending_json");
                    return new ProductionMethodConfiguration(id, recipes.rulesFor(rows.getString("building_type_id")),
                            gson.fromJson(rows.getString("effective_json"), Selections.class).methods(),
                            pending == null ? null : gson.fromJson(pending, Selections.class).methods(),
                            rows.getLong("configuration_revision"), rows.getLong("effective_revision"));
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Cannot read production configuration: " + id, failure);
            }
        }
    }

    @Override
    public void create(ProductionMethodConfiguration initial) {
        if (initial.configurationRevision() != 0 || initial.effectiveRevision() != 0)
            throw new IllegalArgumentException("Initial configuration revision must be zero");
        synchronized (lock) {
            SqliteTransactions.run(connection, () -> {
                try (var statement = connection.prepareStatement("INSERT INTO production_method_configurations(building_id,effective_json,pending_json,configuration_revision,effective_revision) VALUES(?,?,?,?,?)")) {
                    statement.setString(1, initial.buildingId().toString());
                    statement.setString(2, gson.toJson(new Selections(initial.effective().methods())));
                    statement.setString(3, initial.pending().map(p -> gson.toJson(new Selections(p.methods()))).orElse(null));
                    statement.setLong(4, initial.configurationRevision());
                    statement.setLong(5, initial.effectiveRevision());
                    statement.executeUpdate();
                }
                return null;
            });
        }
    }

    @Override
    public void save(ProductionMethodConfiguration changed, long expectedRevision) {
        if (changed.configurationRevision() != Math.incrementExact(expectedRevision))
            throw new IllegalArgumentException("Save exactly one configuration change per commit");
        synchronized (lock) {
            SqliteTransactions.run(connection, () -> {
                try (var statement = connection.prepareStatement("UPDATE production_method_configurations SET effective_json=?,pending_json=?,configuration_revision=?,effective_revision=? WHERE building_id=? AND configuration_revision=?")) {
                    statement.setString(1, gson.toJson(new Selections(changed.effective().methods())));
                    statement.setString(2, changed.pending().map(p -> gson.toJson(new Selections(p.methods()))).orElse(null));
                    statement.setLong(3, changed.configurationRevision());
                    statement.setLong(4, changed.effectiveRevision());
                    statement.setString(5, changed.buildingId().toString());
                    statement.setLong(6, expectedRevision);
                    if (statement.executeUpdate() != 1) throw new ProductionRevisionConflict("Configuration missing or stale: " + changed.buildingId());
                }
                return null;
            });
        }
    }
}
