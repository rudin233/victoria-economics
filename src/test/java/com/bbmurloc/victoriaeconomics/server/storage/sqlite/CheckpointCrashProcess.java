package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Child JVM only. Deliberately bypasses finally/shutdown; never included in the mod. */
public final class CheckpointCrashProcess {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[1]); String mode = args[0];
        var prior = new SqliteCheckpointStore(file).load();
        try (var c = SqliteConnectionGuard.protect(DriverManager.getConnection("jdbc:sqlite::memory:"))) {
            EconomySchema.initialize(c);
            SqliteTransactions.write(c, tx -> { prior.snapshot().install(c, tx); return null; });
            var stock = new SqliteInventoryStore(c, new Object());
            stock.createLocation(new UUID(0, 777), 100); stock.deposit(new UUID(0, 777), Map.of("iron", 64L));
            var image = EconomyCheckpoint.capture(c);
            var next = new EconomyCheckpoint.Receipt(prior.receipt().worldId(), UUID.randomUUID(), prior.receipt().sequence() + 1, image.economicTick(), image.sha256());
            var store = new SqliteCheckpointStore(file, () -> {
                var delegate = SqliteEconomyDatabase.open(file);
                try (var s = delegate.createStatement()) { s.execute("PRAGMA cache_size=1"); } // force real cache spill/journaling before commit
                catch (SQLException failure) { throw new IllegalStateException(failure); }
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
                    if (mode.equals("before") && m.getName().equals("prepareStatement") && ((String) a[0]).startsWith("INSERT INTO \"payroll_runtime\"")) {
                        Files.writeString(Path.of(file + ".phase"), "HALT before physical commit"); Runtime.getRuntime().halt(71);
                    }
                    Object result;
                    try { result = m.invoke(delegate, a); } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    if (mode.equals("after") && m.getName().equals("commit")) {
                        Files.writeString(Path.of(file + ".phase"), "HALT after physical commit, before receipt"); Runtime.getRuntime().halt(72);
                    }
                    return result;
                });
            });
            store.save(prior.receipt(), next, image);
        }
        throw new AssertionError("Crash point was never reached");
    }
}
