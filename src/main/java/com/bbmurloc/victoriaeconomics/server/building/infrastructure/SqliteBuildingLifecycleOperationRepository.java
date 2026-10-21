package com.bbmurloc.victoriaeconomics.server.building.infrastructure;

import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation;
import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation.*;
import com.bbmurloc.victoriaeconomics.server.building.port.BuildingLifecycleOperationRepository;
import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionSettlement;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/** Explicit transaction capability and immutable detached results; no cache publication on savepoint release. */
public final class SqliteBuildingLifecycleOperationRepository implements BuildingLifecycleOperationRepository {
    private final Connection connection;
    private final Object lock;
    public SqliteBuildingLifecycleOperationRepository(Connection connection, Object lock) {
        this.connection=connection; this.lock=lock;
    }
    @Override public Optional<BuildingLifecycleOperation> find(UUID id) {
        synchronized(lock) { return SqliteTransactions.read(connection,tx->read("id",id)); }
    }
    @Override public Optional<BuildingLifecycleOperation> forBuilding(UUID id) {
        synchronized(lock) { return SqliteTransactions.read(connection,tx->read("building_id",id)); }
    }
    @Override public Optional<BuildingLifecycleOperation> verify(Command command) {
        return find(command.id()).map(op->{ requireSameCommand(op,command); return op; });
    }
    @Override public List<BuildingLifecycleOperation> unfinished() {
        synchronized(lock) { return SqliteTransactions.read(connection,tx->{
            var result=new ArrayList<BuildingLifecycleOperation>();
            try(var s=connection.createStatement();var r=s.executeQuery("SELECT * FROM building_lifecycle_operations ORDER BY id")) {
                while(r.next()) result.add(decode(r));
            }
            return List.copyOf(result);
        }); }
    }
    public Optional<BuildingLifecycleOperation> inSnapshot(SqliteTransactions.ReadSession tx,UUID id) throws SQLException {
        tx.requireConnection(connection); return read("building_id",id);
    }
    /** Used at every authoritative production/staffing admission after the caller acquired its write lock. */
    public static boolean holds(Connection c,SqliteTransactions.WriteSession tx,UUID building) throws SQLException {
        return tx.perform(()->{tx.requireConnection(c);
            try(var s=c.prepareStatement("SELECT 1 FROM building_lifecycle_operations WHERE building_id=?")) {
                s.setString(1,building.toString()); try(var r=s.executeQuery()) {return r.next();}
            }
        });
    }
    public static void requireOperational(Connection c,SqliteTransactions.WriteSession tx,UUID building) throws SQLException {
        tx.perform(()->{if(holds(c,tx,building))throw new IllegalStateException("Unfinished building lifecycle operation blocks this command");return null;});
    }
    public Session inTransaction(SqliteTransactions.WriteSession tx) {tx.requireConnection(connection);return new Session(tx);}
    public final class Session {
        private final SqliteTransactions.WriteSession tx;
        private Session(SqliteTransactions.WriteSession tx) {this.tx=tx;}
        public Optional<BuildingLifecycleOperation> find(UUID id) throws SQLException {return tx.perform(()->read("id",id));}
        public Optional<BuildingLifecycleOperation> forBuilding(UUID id) throws SQLException {return tx.perform(()->read("building_id",id));}
        public void create(BuildingLifecycleOperation op) throws SQLException {tx.perform(()->{
            if(op.status()!=Status.QUIESCING)throw new IllegalArgumentException("New lifecycle intent must quiesce first");
            try(var s=connection.prepareStatement("INSERT INTO building_lifecycle_operations VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                var cmd=op.command();s.setString(1,cmd.id().toString());s.setString(2,cmd.buildingId().toString());
                s.setString(3,cmd.seller().toString());s.setString(4,cmd.buyer()==null?null:cmd.buyer().toString());s.setString(5,cmd.kind().name());
                s.setLong(6,cmd.expectedIdentityRevision());s.setLong(7,op.startedTick());s.setString(8,op.payrollSegment().toString());
                s.setString(9,op.batchId()==null?null:op.batchId().toString());s.setString(10,op.settlement()==null?null:op.settlement().name());
                s.setString(11,op.status().name());s.setNull(12,Types.INTEGER);s.setString(13,"");s.setLong(14,0);
                s.executeUpdate();
            }
            return null;
        });}
        public void save(BuildingLifecycleOperation op,long expected) throws SQLException {tx.perform(()->{
            var prior=read("id",op.command().id()).orElseThrow();requireSameCommand(prior,op.command());
            if(prior.revision()!=expected||op.revision()!=Math.incrementExact(expected)||prior.status()!=Status.QUIESCING||op.status()!=Status.BLOCKED
                    ||prior.startedTick()!=op.startedTick()||!prior.payrollSegment().equals(op.payrollSegment())||!Objects.equals(prior.batchId(),op.batchId())||prior.settlement()!=op.settlement())
                throw new IllegalStateException("Invalid lifecycle transition or revision conflict");
            try(var s=connection.prepareStatement("UPDATE building_lifecycle_operations SET status='BLOCKED',blocked_tick=?,blockers=?,revision=? WHERE id=? AND revision=? AND status='QUIESCING'")) {
                s.setLong(1,op.blockedTick());s.setString(2,encodeBlockers(op.blockers()));s.setLong(3,op.revision());s.setString(4,op.command().id().toString());s.setLong(5,expected);
                if(s.executeUpdate()!=1)throw new IllegalStateException("Lifecycle revision conflict");
            }
            return null;
        });}
    }
    public static void requireSameCommand(BuildingLifecycleOperation op,Command command) {
        if(!op.command().equals(command))throw new IllegalArgumentException("OperationId reused with a conflicting complete lifecycle command");
    }
    private Optional<BuildingLifecycleOperation> read(String key,UUID id) throws SQLException {
        try(var s=connection.prepareStatement("SELECT * FROM building_lifecycle_operations WHERE "+key+"=?")) {
            s.setString(1,id.toString());try(var r=s.executeQuery()) {if(!r.next())return Optional.empty();var op=decode(r);if(r.next())throw new IllegalStateException("Multiple lifecycle operations");return Optional.of(op);}
        }
    }
    public static BuildingLifecycleOperation decode(ResultSet r) throws SQLException {
        String buyer=r.getString("buyer_company_id"),batch=r.getString("batch_id"),outcome=r.getString("settlement_outcome");
        long blocked=r.getLong("blocked_tick");Long blockedTick=r.wasNull()?null:blocked;
        return new BuildingLifecycleOperation(new Command(uuid(r.getString("id")),uuid(r.getString("building_id")),CompanyId.parse(r.getString("seller_company_id")),buyer==null?null:CompanyId.parse(buyer),Kind.valueOf(r.getString("kind")),r.getLong("expected_identity_revision")),
                r.getLong("started_tick"),uuid(r.getString("payroll_segment_id")),batch==null?null:uuid(batch),outcome==null?null:ProductionSettlement.Outcome.valueOf(outcome),Status.valueOf(r.getString("status")),blockedTick,decodeBlockers(r.getString("blockers")),r.getLong("revision"));
    }
    private static UUID uuid(String s) {var id=UUID.fromString(s);if(!id.toString().equals(s))throw new IllegalStateException("Canonical lifecycle UUID required");return id;}
    public static String encodeBlockers(Set<Blocker> reasons) {return reasons.stream().map(Enum::name).sorted().collect(Collectors.joining(","));}
    private static Set<Blocker> decodeBlockers(String text) {
        var reasons=EnumSet.noneOf(Blocker.class);
        if(!text.isEmpty())for(String item:text.split(",",-1))if(!reasons.add(Blocker.valueOf(item)))throw new IllegalStateException("Duplicate lifecycle blocker");
        if(!encodeBlockers(reasons).equals(text))throw new IllegalStateException("Canonical lifecycle blocker contract required");return reasons;
    }
}
