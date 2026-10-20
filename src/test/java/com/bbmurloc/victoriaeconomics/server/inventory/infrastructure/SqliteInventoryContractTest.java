package com.bbmurloc.victoriaeconomics.server.inventory.infrastructure;

import com.bbmurloc.victoriaeconomics.server.production.port.ProductionInventoryPort;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.EconomySchema;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionSettlement;
import static com.bbmurloc.victoriaeconomics.server.production.domain.ProductionSettlement.Outcome.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SqliteInventoryContractTest {
    @TempDir
    Path directory;
    private Connection connection;
    private SqliteInventoryStore store;
    private ProductionInventoryPort port;
    private final UUID location = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("inventory.sqlite"));
        EconomySchema.initialize(connection);
        store = new SqliteInventoryStore(connection, new Object());
        port = store;
    }

    @AfterEach
    void close() throws Exception {
        connection.close();
    }

    private String storedJson() throws Exception {
        try (var statement = connection.prepareStatement("SELECT state_json FROM inventory_locations WHERE location_id = ?")) {
            statement.setString(1, location.toString());
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private Set<String> storedKeys(String jsonPath) throws Exception {
        try (var statement = connection.prepareStatement("SELECT key FROM inventory_locations, json_each(state_json, ?) WHERE location_id = ?")) {
            statement.setString(1, jsonPath);
            statement.setString(2, location.toString());
            try (var rows = statement.executeQuery()) {
                Set<String> keys = new HashSet<>();
                while (rows.next()) keys.add(rows.getString(1));
                return keys;
            }
        }
    }

    @Test
    void capacityStatusKeepsExactBoundaryAndCommittedOverflowRules() {
        store.createLocation(location, 10);
        store.deposit(location, Map.of("ore", 10L));
        assertFalse(port.storageStatus(location).overCapacity());
        UUID batch = UUID.randomUUID();
        var inputs = Map.of("ore", 2L);
        port.reserve(location, batch, inputs);
        assertFalse(port.storageStatus(location).overCapacity());
        port.settle(location, new ProductionSettlement(batch, COMPLETED, inputs, Map.of("metal", 3L)));

        var status = port.storageStatus(location);
        assertTrue(status.overCapacity()); // 10 - 2 + 3 = 11; committed intake is allowed.
        assertThrows(IllegalStateException.class, () -> store.deposit(location, Map.of("ore", 1L)));
        assertThrows(IllegalStateException.class, () -> port.reserve(location, UUID.randomUUID(), inputs));
        store.withdraw(location, Map.of("metal", 1L));
        assertFalse(port.storageStatus(location).overCapacity());
        assertTrue(status.overCapacity()); // A prior query snapshot cannot authorize future operations.
        port.reserve(location, UUID.randomUUID(), inputs);
    }

    @Test
    void fullRecipeReservationIsAtomicExclusiveAndLocalAndReleaseIsRepeatable() {
        UUID other = UUID.randomUUID(), batch = UUID.randomUUID();
        store.createLocation(location, 100);
        store.createLocation(other, 1000);
        store.deposit(location, Map.of("ore", 10L, "wood", 1L));
        store.deposit(other, Map.of("ore", 100L, "wood", 100L));
        var inputs = Map.of("ore", 3L, "wood", 2L);
        assertThrows(IllegalStateException.class, () -> port.reserve(location, batch, inputs));
        assertTrue(store.inspect(location).reservations().isEmpty());
        assertTrue(store.inspect(other).reservations().isEmpty());

        store.deposit(location, Map.of("wood", 1L));
        port.reserve(location, batch, inputs);
        port.reserve(location, batch, inputs);
        assertTrue(port.isReserved(location, batch, inputs));
        assertFalse(port.isReserved(other, batch, inputs));
        assertThrows(IllegalStateException.class, () -> store.withdraw(location, Map.of("ore", 8L)));
        assertThrows(IllegalStateException.class, () -> port.reserve(location, UUID.randomUUID(), inputs));
        assertThrows(IllegalStateException.class, () -> port.reserve(location, batch, Map.of("ore", 1L)));
        assertEquals(1, store.inspect(location).reservations().size());

        port.release(location, batch);
        port.release(location, batch);
        assertTrue(store.inspect(location).reservations().isEmpty());
        assertEquals(Map.of("ore", 10L, "wood", 2L), store.inspect(location).quantities());
    }

    @Test
    void completeReceiptSurvivesRestartAndConflictingAbortCannotResettle() throws Exception {
        UUID batch = UUID.randomUUID();
        var inputs = Map.of("ore", 10L);
        var outputs = Map.of("metal", 4L);
        store.createLocation(location, 100);
        store.deposit(location, Map.of("ore", 100L));
        port.reserve(location, batch, inputs);
        var receipt = port.settle(location, new ProductionSettlement(batch, COMPLETED, inputs, outputs));
        assertEquals(batch, receipt.batchId());
        assertEquals(Map.of("ore", 90L, "metal", 4L), store.inspect(location).quantities());
        assertTrue(store.inspect(location).reservations().isEmpty());
        assertEquals(Map.of("ore", 10L), store.inspect(location).settlements().get(batch).consumed());

        connection.close();
        open();
        assertEquals(receipt, port.settle(location, new ProductionSettlement(batch, COMPLETED, inputs, outputs)));
        var before = store.inspect(location);
        assertThrows(IllegalStateException.class, () -> port.settle(location, new ProductionSettlement(batch, ABORTED, inputs, outputs)));
        assertEquals(before, store.inspect(location));
        assertEquals(1, before.settlements().size());
        assertEquals(Map.of("metal", 4L), before.settlements().get(batch).produced());
    }

    @Test
    void failedPersistenceDoesNotReturnAReceiptAndLeavesCommitmentAvailableForRetry() throws Exception {
        UUID batch = UUID.randomUUID();
        var inputs = Map.of("ore", 10L);
        var outputs = Map.of("metal", 4L);
        store.createLocation(location, 100);
        store.deposit(location, Map.of("ore", 10L));
        port.reserve(location, batch, inputs);
        String before = storedJson();
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TRIGGER reject_inventory_save BEFORE UPDATE ON inventory_locations BEGIN SELECT RAISE(ABORT, 'injected inventory save failure'); END");
        }
        assertThrows(IllegalStateException.class, () -> port.settle(location, new ProductionSettlement(batch, COMPLETED, inputs, outputs)));
        assertEquals(before, storedJson());
        assertTrue(port.isReserved(location, batch, inputs));
        assertTrue(store.inspect(location).settlements().isEmpty());
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TRIGGER reject_inventory_save");
        }
        assertEquals(batch, port.settle(location, new ProductionSettlement(batch, COMPLETED, inputs, outputs)).batchId());
        assertEquals(batch, port.settle(location, new ProductionSettlement(batch, COMPLETED, inputs, outputs)).batchId());
        assertEquals(4L, store.inspect(location).quantities().get("metal"));
        assertTrue(store.inspect(location).reservations().isEmpty());
        assertEquals(1, store.inspect(location).settlements().size());
    }

    @Test
    void existingV4JsonLoadsWithoutRewriteAndReceiptsKeepFrozenCommands() throws Exception {
        UUID reserved = UUID.randomUUID(), settled = UUID.randomUUID();
        String json = """
                {"formatVersion":4,"snapshot":{"location":"%s","capacity":100,"quantities":{"ore":12,"metal":1},
                 "reservations":{"%s":{"ore":5}},
                 "settlements":{"%s":{"command":{"batchId":"%s","outcome":"COMPLETED","inputs":{"ore":10},"outputs":{"metal":5}}}}}}
                """.formatted(location, reserved, settled, settled);
        try (var statement = connection.prepareStatement("INSERT INTO inventory_locations(location_id, state_json, revision) VALUES(?, ?, 0)")) {
            statement.setString(1, location.toString());
            statement.setString(2, json);
            statement.executeUpdate();
        }
        assertFalse(port.storageStatus(location).overCapacity());
        assertTrue(port.isReserved(location, reserved, Map.of("ore", 5L)));
        assertEquals(json, storedJson()); // Read-only production queries must not rewrite an old save.
        var before = store.inspect(location);
        assertEquals(settled, port.settle(location, new ProductionSettlement(settled, COMPLETED, Map.of("ore", 10L), Map.of("metal", 5L))).batchId());
        assertEquals(before, store.inspect(location));

        port.settle(location, new ProductionSettlement(reserved, ABORTED, Map.of("ore", 5L), Map.of("metal", 2L)));
        assertEquals(Set.of("formatVersion", "snapshot"), storedKeys("$"));
        assertEquals(Set.of("location", "capacity", "quantities", "reservations", "settlements"), storedKeys("$.snapshot"));
        assertEquals(Set.of("command"), storedKeys("$.snapshot.settlements.\"" + reserved + "\""));
    }
}
