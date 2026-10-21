package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/** A hot rollback journal may require writes just to read. Preflight a COPY before touching the source. */
final class CheckpointFileRecovery {
    private CheckpointFileRecovery() {}
    private static final List<String> SUFFIXES = List.of("", "-wal", "-journal");
    private record Verified(EconomyCheckpoint.Recovered image, Map<String, String> sourceHashes) {}

    static EconomyCheckpoint.Recovered readCopy(Path file, RuntimeException original) {
        try { return verifyCopy(file).image; }
        catch (IOException | SQLException | RuntimeException failure) {
            original.addSuppressed(failure); throw original;
        }
    }
    static Connection writableAfterVerifiedRecovery(Path file, RuntimeException original) {
        Connection c = null;
        try {
            var verified = verifyCopy(file);
            if (!verified.sourceHashes.equals(hashes(file))) throw new IllegalStateException("Checkpoint files changed during recovery preflight");
            var config = new SQLiteConfig(); config.resetOpenMode(SQLiteOpenMode.CREATE);
            c = SqliteConnectionGuard.protect(DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), config.toProperties()));
            // Only now may SQLite recover the real CURRENT-format journal. Old worlds never get here.
            EconomySchema.initialize(c, false);
            if (!EconomyCheckpoint.readVerified(c).receipt().equals(verified.image.receipt()))
                throw new IllegalStateException("Recovered checkpoint differs from the validated copy");
            return c;
        } catch (IOException | SQLException | RuntimeException failure) {
            if (c != null) SqliteConnectionGuard.quarantine(c, failure);
            original.addSuppressed(failure); throw original;
        }
    }
    private static Verified verifyCopy(Path file) throws IOException, SQLException {
        rejectSuperJournal(file);
        var before = hashes(file);
        Path directory = Files.createTempDirectory("ve-checkpoint-preflight-");
        Path target = directory.resolve("economy.sqlite");
        try {
            for (String suffix : SUFFIXES) {
                Path source = Path.of(file + suffix);
                if (Files.exists(source)) Files.copy(source, Path.of(target + suffix));
            }
            if (!before.equals(hashes(file))) throw new IllegalStateException("Checkpoint files changed while copying; no source recovery attempted");
            EconomyCheckpoint.Recovered image;
            try (var c = SqliteConnectionGuard.protect(DriverManager.getConnection("jdbc:sqlite:" + target))) {
                EconomySchema.initialize(c, false); // SQLite hot-journal recovery writes only the isolated copy
                image = EconomyCheckpoint.readVerified(c);
            }
            return new Verified(image, before);
        } finally {
            // Only these known files in our newly created temp directory. Never delete/move a source journal.
            for (String suffix : List.of("", "-journal", "-wal", "-shm")) Files.deleteIfExists(Path.of(target + suffix));
            Files.delete(directory);
        }
    }
    private static Map<String, String> hashes(Path file) throws IOException {
        var result = new LinkedHashMap<String, String>();
        for (String suffix : SUFFIXES) {
            Path path = Path.of(file + suffix);
            if (!Files.exists(path)) { result.put(suffix, "absent"); continue; }
            try {
                var hash = MessageDigest.getInstance("SHA-256");
                try (var in = new DigestInputStream(Files.newInputStream(path), hash)) { in.transferTo(OutputStream.nullOutputStream()); }
                result.put(suffix, HexFormat.of().formatHex(hash.digest()));
            } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        }
        return result;
    }
    private static void rejectSuperJournal(Path file) throws IOException {
        Path journal = Path.of(file + "-journal");
        if (!Files.exists(journal) || Files.size(journal) < 16) return;
        try (var in = new RandomAccessFile(journal.toFile(), "r")) {
            in.seek(in.length() - 8); byte[] tail = new byte[8]; in.readFully(tail);
            if (HexFormat.of().formatHex(tail).equals("d9d505f920a163d7"))
                throw new IllegalStateException("Cross-database super-journal recovery is unsupported; source files untouched");
        }
    }
}
