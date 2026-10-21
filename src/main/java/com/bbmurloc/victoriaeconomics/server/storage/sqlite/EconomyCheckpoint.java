package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.diagnostics.EconomyDiagnostics;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Immutable whole-world rows; no live aggregates, JDBC handles or Minecraft objects escape capture. */
public final class EconomyCheckpoint {
    public record Receipt(UUID worldId, UUID checkpointId, long sequence, long economicTick, String sha256) {
        public Receipt {
            Objects.requireNonNull(worldId); Objects.requireNonNull(checkpointId);
            if (sequence <= 0 || economicTick < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid checkpoint identity");
        }
    }
    private record Table(String name, List<String> columns, List<List<Object>> rows) {}
    private final List<Table> tables;
    private final long tick;
    private final String digest;

    private EconomyCheckpoint(List<Table> tables, long tick) {
        this.tables = List.copyOf(tables); this.tick = tick; digest = digest(tables);
    }
    public long economicTick() { return tick; }
    public String sha256() { return digest; }
    public long rowCount() { return tables.stream().mapToLong(t -> t.rows.size()).sum(); }

    /** Caller holds the world's economy lock; committed is runtime visibility, not disk durability. */
    public static EconomyCheckpoint capture(Connection connection) {
        try (var ignored = EconomyDiagnostics.scope("checkpoint.capture")) {
            return SqliteTransactions.read(connection, tx -> captureInside(connection));
        }
    }
    public record Recovered(Receipt receipt, EconomyCheckpoint snapshot) {}
    public static Recovered readVerified(Connection connection) {
        return SqliteTransactions.read(connection, tx -> {
            var receipt = receipt(connection).orElseThrow(() -> new IllegalStateException("No complete economic checkpoint"));
            var snapshot = captureInside(connection);
            requireMatches(receipt, snapshot);
            return new Recovered(receipt, snapshot);
        });
    }
    private static EconomyCheckpoint captureInside(Connection connection) throws SQLException {
        long tick = requireCompleteTick(connection);
        var tables = new ArrayList<Table>();
        for (String name : EconomySchema.checkpointTables()) {
            var columns = new ArrayList<String>();
            try (var s = connection.createStatement(); var r = s.executeQuery("PRAGMA table_info(" + quote(name) + ")")) {
                while (r.next()) columns.add(r.getString("name"));
            }
            if (columns.isEmpty()) throw new IllegalStateException("Missing checkpoint table " + name);
            var rows = new ArrayList<List<Object>>();
            String order = String.join(",", columns.stream().map(EconomyCheckpoint::quote).toList());
            try (var s = connection.createStatement(); var r = s.executeQuery("SELECT * FROM " + quote(name) + " ORDER BY " + order)) {
                while (r.next()) {
                    var values = new ArrayList<Object>();
                    for (int i = 1; i <= columns.size(); i++) {
                        Object value = r.getObject(i);
                        if (value instanceof Integer n) value = n.longValue();
                        if (value != null && !(value instanceof Long) && !(value instanceof String))
                            throw new IllegalStateException("Unsupported checkpoint value; no numeric conversion allowed");
                        values.add(value);
                    }
                    rows.add(Collections.unmodifiableList(values)); // null is a meaningful SQL value
                }
            }
            tables.add(new Table(name, List.copyOf(columns), List.copyOf(rows)));
        }
        return new EconomyCheckpoint(tables, tick);
    }

    /** A failed/in-flight economic Tick cannot replace a completed, trusted recovery point. */
    private static long requireCompleteTick(Connection c) throws SQLException {
        long tick;
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT t.accepted_tick,t.production_callback_returned_tick,p.processed_tick,q.processed_period,t.first_uncertain_production_tick FROM economic_time t JOIN payroll_runtime p ON p.id=t.id JOIN payroll_payment_runtime q ON q.id=t.id")) {
            if (!r.next()) throw new IllegalStateException("Checkpoint has no complete economic clock");
            tick = r.getLong(1);
            if (r.getLong(2) != tick || r.getLong(3) != tick || r.getLong(4) != tick / 1200 || r.getObject(5) != null || r.next())
                throw new IllegalStateException("Checkpoint waits for time, payroll and payment cursors");
        }
        try (var s = c.prepareStatement("SELECT 1 FROM production_executions e JOIN economic_buildings b ON b.id=e.building_id LEFT JOIN production_batches batch ON batch.batch_id=e.current_batch_id WHERE b.lifecycle='EXISTING' AND (e.last_completed_tick<>? OR e.processing_tick IS NOT NULL OR e.boundary<>'NONE' OR batch.status IN ('SETTLING_COMPLETED','SETTLING_ABORTED')) UNION ALL SELECT 1 FROM production_start_intents")) {
            s.setLong(1, tick);
            try (var r = s.executeQuery()) { if (r.next()) throw new IllegalStateException("Checkpoint waits for completed building business transactions"); }
        }
        return tick;
    }

    /** Restore all versioned rows in one existing WriteSession. FK checks are deferred, never disabled. */
    public void install(Connection c, SqliteTransactions.WriteSession tx) throws SQLException {
        tx.perform(() -> {
            tx.requireConnection(c);
            try (var s = c.createStatement()) {
                s.execute("PRAGMA defer_foreign_keys=ON");
                for (var table : tables.reversed()) s.executeUpdate("DELETE FROM " + quote(table.name));
            }
            for (var table : tables) {
                String placeholders = String.join(",", Collections.nCopies(table.columns.size(), "?"));
                try (var s = c.prepareStatement("INSERT INTO " + quote(table.name) + " (" + String.join(",", table.columns.stream().map(EconomyCheckpoint::quote).toList()) + ") VALUES(" + placeholders + ")")) {
                    for (var row : table.rows) {
                        for (int i = 0; i < row.size(); i++) s.setObject(i + 1, row.get(i));
                        s.executeUpdate();
                    }
                }
            }
            try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA foreign_key_check")) {
                if (r.next()) throw new IllegalStateException("Incomplete checkpoint foreign keys");
            }
            return null;
        });
    }

    /** Expensive historical validation occurs on a detached private memory image, on the writer. */
    public void validate() {
        try (var c = SqliteConnectionGuard.protect(DriverManager.getConnection("jdbc:sqlite::memory:"))) {
            EconomySchema.initialize(c);
            SqliteTransactions.write(c, tx -> { install(c, tx); return null; });
            EconomySchema.validateExisting(c);
            if (!capture(c).sha256().equals(digest)) throw new IllegalStateException("Checkpoint image failed round-trip validation");
        } catch (SQLException failure) { throw new IllegalStateException("Cannot validate checkpoint image", failure); }
    }

    public static Optional<Receipt> receipt(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM economic_checkpoint")) {
            if (!r.next()) return Optional.empty();
            var receipt = new Receipt(canonicalId(r.getString("world_id")), canonicalId(r.getString("checkpoint_id")),
                    r.getLong("sequence"), r.getLong("economic_tick"), r.getString("content_sha256"));
            if (r.next()) throw new IllegalStateException("Multiple checkpoint identities");
            return Optional.of(receipt);
        }
    }
    public static void saveReceipt(Connection c, SqliteTransactions.WriteSession tx, Receipt receipt) throws SQLException {
        tx.perform(() -> {
            tx.requireConnection(c);
            try (var s = c.prepareStatement("INSERT OR REPLACE INTO economic_checkpoint VALUES(1,?,?,?,?,?)")) {
                s.setString(1, receipt.worldId.toString()); s.setString(2, receipt.checkpointId.toString());
                s.setLong(3, receipt.sequence); s.setLong(4, receipt.economicTick); s.setString(5, receipt.sha256); s.executeUpdate();
            }
            return null;
        });
    }
    public static void requireMatches(Receipt receipt, EconomyCheckpoint snapshot) {
        if (receipt.economicTick != snapshot.tick || !receipt.sha256.equals(snapshot.digest))
            throw new IllegalStateException("Checkpoint metadata and complete business image disagree; no recovery attempted");
    }
    private static UUID canonicalId(String text) {
        var id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw new IllegalArgumentException("Noncanonical checkpoint identity");
        return id;
    }
    private static String quote(String name) {
        if (!name.matches("[a-z_]+")) throw new IllegalArgumentException("Unexpected versioned SQL identifier");
        return '"' + name + '"';
    }
    private static String digest(List<Table> tables) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), hash));
            out.writeInt(EconomySchema.VERSION); out.writeInt(tables.size());
            for (var table : tables) {
                string(out, table.name); out.writeInt(table.columns.size());
                for (String column : table.columns) string(out, column);
                out.writeInt(table.rows.size());
                for (var row : table.rows) for (Object value : row) {
                    if (value == null) out.writeByte(0);
                    else if (value instanceof Long n) { out.writeByte(1); out.writeLong(n); }
                    else { out.writeByte(2); string(out, (String) value); }
                }
            }
            out.flush(); return HexFormat.of().formatHex(hash.digest());
        } catch (IOException | NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
}
