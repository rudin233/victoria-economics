import fs from 'node:fs';
import path from 'node:path';

// Run after all servers/builds have stopped. Reads JSON only; never opens a world database.
const directory = process.argv[2] ?? 'build';
const results = [];
const labels = new Set((process.argv[4] ?? 'baseline,optimized').split(','));
for (const name of fs.readdirSync(directory).sort()) {
  const file = path.join(directory, name, 'performance.json');
  if (!name.startsWith('task-e-gametest-p_') || !fs.existsSync(file)) continue;
  const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (!labels.has(raw.label)) continue;
  const t = raw.sample.timings, c = raw.sample.counts, n = raw.serverTick.count;
  if (n !== 1300 || (raw.details !== false && t['context.tick'].count !== n)) throw Error(`Incomplete or unaligned sample: ${name}`);
  const perTickMs = key => (t[key]?.count ?? 0) * (t[key]?.meanMs ?? 0) / n;
  // Every measured active scenario starts at zero and warms for 150 ticks (<200).
  // Seven 200-tick completions per building occur in the next 1300 ticks, each with two
  // standalone Journal DML commits. These autocommits are not SqliteTransactions.run scopes.
  const buildings = raw.scenario === 'empty' ? 0 : raw.scenario.endsWith('32') ? 32 : raw.scenario.endsWith('16') ? 16 : 4;
  const journalCommits = raw.scenario.startsWith('active') || raw.scenario === 'history4' ? 14 * buildings : 0;
  results.push({
    run: name, label: raw.label, scenario: raw.scenario, samples: n,
    server: raw.serverTick, observedUncappedTps: raw.observedTps,
    capped20TpsEquivalent: Math.min(20, raw.observedTps),
    economyContextMeanMs: raw.details === false ? null : perTickMs('context.tick'),
    // Native saving happens before the economy Post callback: capture/wait scopes do not overlap Context.
    economyMeanMs: raw.details === false ? null : perTickMs('context.tick') + perTickMs('checkpoint.capture') + perTickMs('checkpoint.wait'),
    economyShare: raw.details === false ? null : (perTickMs('context.tick') + perTickMs('checkpoint.capture') + perTickMs('checkpoint.wait')) / raw.serverTick.meanMs,
    minecraftBeforePost: raw.minecraftBeforePost,
    nonEconomyMeanMs: raw.details === false ? null : raw.serverTick.meanMs - perTickMs('context.tick') - perTickMs('checkpoint.capture') - perTickMs('checkpoint.wait'),
    stagesMsPerTick: Object.fromEntries(Object.keys(t).map(key => [key, perTickMs(key)])),
    managedTransactionsPerTick: raw.details === false ? null : (c['tx.physical'] ?? 0) / n,
    journalAutocommitsPerTick: journalCommits / n,
    writesPerTick: raw.details === false ? null : (t['sqlite.write']?.count ?? 0) / n,
    dirtyWriteScopesPerTick: raw.details === false ? null : (c['tx.write.withDml'] ?? 0) / n,
    emptyWriteScopesPerTick: raw.details === false ? null : (c['tx.write.withoutDml'] ?? 0) / n,
    readSqlPerTick: raw.details === false ? null : (c['sql.read'] ?? 0) / n,
    dmlSqlPerTick: raw.details === false ? null : (c['sql.dml'] ?? 0) / n,
    // One accepted-time query is made by the test harness outside the economy Context per sample.
    testHarnessReadSqlPerTick: 1,
    raw,
    runtimeDatabase: raw.runtimeStorage ?? 'file SQLite; per-operation durability',
    // These jobs include fixture/warmup/final verification saves. They are NOT per-Tick SQL scopes.
    checkpointJobsWholeRun: raw.checkpoint?.saved ?? null,
    nativeSaveRequestsWholeRun: raw.nativeSave?.requests ?? null,
    checkpointTimingsWholeRun: raw.checkpointTimings ?? null,
    preflight: fs.existsSync(path.join(directory, name, 'preflight-performance.json'))
      ? JSON.parse(fs.readFileSync(path.join(directory, name, 'preflight-performance.json'), 'utf8')) : null
  });
}
const output = process.argv[3] ?? 'build/task-p-performance-results.json';
fs.writeFileSync(output, JSON.stringify({
  note: 'Inclusive timings overlap. Managed scopes exclude Journal autocommits. In the checkpoint architecture all main-thread SQL scopes/Journal commits are RAM transactions, not disk writes. Checkpoint jobs count actual complete-image disk transactions and include final verification saves outside sampling. Uncapped GameTest throughput is not live-player TPS. SQL counts include the harness read.',
  results
}, null, 2) + '\n');
console.log('| Label | Scenario | N | Mean | p50 | p95 | p99 | Max | VE mean | uncapped ticks/s |');
console.log('|---|---|---:|---:|---:|---:|---:|---:|---:|---:|');
for (const x of results) {
  const s = x.server;
  console.log(`| ${x.label} | ${x.scenario} | ${x.samples} | ${[s.meanMs, s.p50Ms, s.p95Ms, s.p99Ms, s.maxMs, x.economyMeanMs, x.observedUncappedTps].map(v => v === null ? 'n/a' : v.toFixed(3)).join(' | ')} |`);
}
