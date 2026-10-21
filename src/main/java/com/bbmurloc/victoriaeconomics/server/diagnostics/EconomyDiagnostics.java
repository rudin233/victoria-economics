package com.bbmurloc.victoriaeconomics.server.diagnostics;

import java.util.*;

/** Optional, bounded, thread-owned observations. Never supplies time, readiness, or transaction authority. */
public final class EconomyDiagnostics {
    private EconomyDiagnostics() {}
    private static volatile Recording recording;
    private static final Scope DISABLED = new Scope(null, null, 0, false);
    private static final int SAMPLE_LIMIT = 1_000_000;

    public record Summary(long count, double meanMs, double p50Ms, double p95Ms, double p99Ms, double maxMs) {}
    public record Snapshot(Map<String, Summary> timings, Map<String, Long> counts) {}

    /** Install only for an explicitly requested diagnostic run; other threads are not observed. */
    public static Recording start() {
        if (recording != null) throw new IllegalStateException("A diagnostic recording is already active");
        var next = new Recording();
        recording = next;
        return next;
    }
    public static Scope scope(String name) { return scope(name, false); }
    public static Scope transaction() { return scope("sqlite.transaction", true); }
    private static Scope scope(String name, boolean transaction) {
        var r = recording;
        return r == null || r.owner != Thread.currentThread() ? DISABLED : new Scope(r, name, System.nanoTime(), transaction);
    }
    public static void measure(String name, Runnable operation) {
        try (var scope = scope(name)) { operation.run(); }
    }
    public static void count(String name) {
        var r = recording;
        if (r != null && r.owner == Thread.currentThread()) r.count(name);
    }
    /** Counts successful DML invocations affecting rows, not inferred fsyncs or economic changes. */
    public static void sql(String sql, String method, Object result, long elapsedNanos) {
        var r = recording;
        if (r == null || r.owner != Thread.currentThread()) return;
        String text = sql == null ? "" : sql.stripLeading().toUpperCase(Locale.ROOT);
        String kind = text.startsWith("SELECT") || method.equals("executeQuery") ? "read"
                : text.startsWith("UPDATE") || text.startsWith("INSERT") || text.startsWith("DELETE") ? "dml" : "other";
        r.add("sql." + kind, elapsedNanos);
        r.count("sql." + kind);
        if (kind.equals("dml") && (!(result instanceof Number n) || n.longValue() > 0)) r.mutations++;
    }
    public static boolean enabled() {
        var r = recording;
        return r != null && r.owner == Thread.currentThread();
    }
    public static final class Scope implements AutoCloseable {
        private final Recording recording;
        private final String name;
        private final long started;
        private final long mutations;
        private final boolean transaction;
        private boolean closed;
        private Scope(Recording recording, String name, long started, boolean transaction) {
            this.recording = recording; this.name = name; this.started = started; this.transaction = transaction;
            mutations = recording == null ? 0 : recording.mutations;
        }
        @Override public void close() {
            if (recording == null || closed) return;
            closed = true;
            recording.add(name, System.nanoTime() - started);
            if (transaction) recording.count(recording.mutations == mutations ? "tx.withoutDml" : "tx.withDml");
            if (name.equals("sqlite.write")) recording.count(recording.mutations == mutations ? "tx.write.withoutDml" : "tx.write.withDml");
        }
    }
    public static final class Recording implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final Map<String, List<Long>> timings = new TreeMap<>();
        private final Map<String, Long> counts = new TreeMap<>();
        private long mutations;
        private Recording() {}
        private void add(String name, long nanos) {
            var samples = timings.computeIfAbsent(name, key -> new ArrayList<>());
            if (samples.size() < SAMPLE_LIMIT) samples.add(nanos);
            else count("dropped." + name);
        }
        private void count(String name) { counts.merge(name, 1L, Long::sum); }
        /** Called between measured operations, never from inside a transaction. */
        public Snapshot snapshot() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Diagnostic recording belongs to another thread");
            var result = new TreeMap<String, Summary>();
            timings.forEach((name, samples) -> result.put(name, summarize(samples)));
            return new Snapshot(Map.copyOf(result), Map.copyOf(counts));
        }
        public void reset() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Diagnostic recording belongs to another thread");
            timings.clear(); counts.clear(); mutations = 0;
        }
        /** Observer-only event count; never used as an economic cursor or transaction permission. */
        public long eventCount(String name) {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Diagnostic recording belongs to another thread");
            return counts.getOrDefault(name, 0L);
        }
        @Override public void close() { if (recording == this) recording = null; }
    }
    public static Summary summarize(Collection<Long> samples) {
        if (samples.isEmpty()) return new Summary(0, 0, 0, 0, 0, 0);
        var sorted = samples.stream().sorted().toList();
        return new Summary(sorted.size(), sorted.stream().mapToDouble(Long::doubleValue).average().orElseThrow()/1e6,
                percentile(sorted, .50), percentile(sorted, .95), percentile(sorted, .99), sorted.getLast()/1e6);
    }
    private static double percentile(List<Long> sorted, double fraction) {
        return sorted.get((int)Math.ceil(sorted.size()*fraction)-1)/1e6;
    }
}
