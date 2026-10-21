package com.bbmurloc.victoriaeconomics.server.storage;

import com.bbmurloc.victoriaeconomics.server.diagnostics.EconomyDiagnostics;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.EconomyCheckpoint.Receipt;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** One transactional memory authority. Disk contains only a complete recovery checkpoint. */
public final class EconomyDatabase implements AutoCloseable {
    private final String memoryUrl = "jdbc:sqlite:file:ve_" + UUID.randomUUID() + "?mode=memory&cache=shared";
    private final Connection keeper;
    private final Connection connection;
    private final FileChannel lockChannel;
    private final FileLock fileLock;
    private final EconomicCheckpointWriter checkpoints;
    private final SqliteCheckpointStore checkpointStore;
    private boolean closed;
    private CompletableFuture<Receipt> latest;

    private EconomyDatabase(Path worldRoot) throws IOException, SQLException {
        Path directory = worldRoot.resolve("victoria_economics"), file = directory.resolve("economy.sqlite");
        var store = new SqliteCheckpointStore(file);
        // Reject old files before creating a sidecar or obtaining a writable database handle.
        if (Files.exists(file)) store.load();
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("economy.runtime.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (IOException | RuntimeException failure) { lockChannel.close(); throw failure; }
        if (acquired == null) { lockChannel.close(); throw new IllegalStateException("Another VE runtime owns this world's checkpoint"); }
        fileLock = acquired;
        Connection initialKeeper = null, initialConnection = null;
        EconomicCheckpointWriter initialWriter = null;
        try (var ignored = EconomyDiagnostics.scope("checkpoint.restore")) {
            var recovered = Files.exists(file) ? store.load() : null;
            initialKeeper = connectMemory();
            EconomySchema.initialize(initialKeeper);
            if (recovered != null) {
                var image = recovered; Connection target = initialKeeper;
                SqliteTransactions.write(target, tx -> {
                    image.snapshot().install(target, tx);
                    EconomyCheckpoint.saveReceipt(target, tx, image.receipt()); return null;
                });
                EconomySchema.validateExisting(target);
                EconomyCheckpoint.requireMatches(image.receipt(), EconomyCheckpoint.capture(target));
            }
            Receipt receipt;
            if (recovered == null) {
                var empty = EconomyCheckpoint.capture(initialKeeper);
                receipt = new Receipt(UUID.randomUUID(), UUID.randomUUID(), 1, 0, empty.sha256());
                store.save(null, receipt, empty);
            } else receipt = recovered.receipt();
            initialConnection = connectMemory();
            // The worker itself retains ownership even if shutdown times out and the Context is dropped.
            // Merely keeping the lock in this otherwise collectible runtime would be insufficient.
            var ownership = fileLock;
            initialWriter = new EconomicCheckpointWriter(receipt, (previous, next, image) -> {
                if (!ownership.isValid()) throw new IllegalStateException("Economic checkpoint file ownership was lost");
                return store.save(previous, next, image);
            });
            keeper = initialKeeper; connection = initialConnection; checkpoints = initialWriter; checkpointStore = store;
            latest = CompletableFuture.completedFuture(receipt);
        } catch (SQLException | RuntimeException | Error failure) {
            if (initialWriter != null) try { initialWriter.close(); } catch (RuntimeException close) { failure.addSuppressed(close); }
            close(initialConnection, failure); close(initialKeeper, failure);
            fileLock.release(); lockChannel.close(); throw failure;
        }
    }
    public static EconomyDatabase open(Path worldRoot) {
        try (var ignored = EconomyDiagnostics.scope("runtime.open")) { return new EconomyDatabase(worldRoot); }
        catch (IOException | SQLException | RuntimeException failure) {
            throw new IllegalStateException("Cannot open VE memory runtime / complete checkpoint; no migration was attempted", failure);
        }
    }
    private Connection connectMemory() throws SQLException {
        var c = SqliteConnectionGuard.protect(DriverManager.getConnection(memoryUrl));
        try (var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000");
            // Never silently fall back to a temporary/disk database.
            try (var r = s.executeQuery("PRAGMA database_list")) {
                while (r.next()) if (r.getString("name").equals("main") && !r.getString("file").isEmpty())
                    throw new IllegalStateException("VE runtime is not an in-memory database");
            }
            return c;
        } catch (SQLException | RuntimeException failure) { close(c, failure); throw failure; }
    }
    public Connection getConnection() { requireOpen(); return connection; }

    /** Verify current commits in the SAME memory authority, never the older disk checkpoint. */
    public Connection openVerificationConnection() {
        requireOpen(); Connection c = null;
        try { c = connectMemory(); EconomySchema.validateExisting(c); return c; }
        catch (SQLException | RuntimeException failure) { close(c, failure); throw new IllegalStateException("Cannot verify current memory authority", failure); }
    }
    /** Caller holds the shared economy lock. Capture is synchronous; persistence is not. */
    public CompletableFuture<Receipt> requestCheckpoint() {
        requireOpen();
        try { latest = checkpoints.submit(EconomyCheckpoint.capture(connection)); return latest.copy(); }
        catch (RuntimeException failure) {
            // An older successful future cannot masquerade as this rejected save.
            latest = CompletableFuture.failedFuture(failure); throw failure;
        }
    }
    public Receipt awaitLatestCheckpoint() { requireOpen(); return EconomicCheckpointWriter.await(latest); }
    public EconomicCheckpointWriter.Status checkpointStatus() { return checkpoints.status(); }
    public java.util.List<SqliteCheckpointStore.Timing> checkpointTimings() { return checkpointStore.timings(); }
    private void requireOpen() { if (closed) throw new IllegalStateException("VE runtime is closed"); }

    @Override public void close() {
        if (closed) return;
        RuntimeException failure = null;
        try { EconomicCheckpointWriter.await(requestCheckpoint()); }
        catch (RuntimeException save) { failure = save; }
        try { checkpoints.close(); }
        catch (RuntimeException drain) {
            if (failure != null) drain.addSuppressed(failure);
            // Keep the lock while a writer may still run; another runtime must not take over.
            throw drain;
        }
        closed = true;
        try { connection.close(); keeper.close(); fileLock.release(); lockChannel.close(); }
        catch (SQLException | IOException close) {
            if (failure == null) failure = new IllegalStateException("Cannot close economic runtime", close); else failure.addSuppressed(close);
        }
        if (failure != null) throw failure;
    }
    private static void close(Connection c, Throwable failure) {
        if (c != null) try { c.close(); } catch (SQLException close) { failure.addSuppressed(close); }
    }
}
