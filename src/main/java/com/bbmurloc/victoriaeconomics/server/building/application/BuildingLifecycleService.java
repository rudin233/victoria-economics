package com.bbmurloc.victoriaeconomics.server.building.application;

import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation;
import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation.*;
import com.bbmurloc.victoriaeconomics.server.building.infrastructure.SqliteBuildingLifecycleOperationRepository;
import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.SqlitePayrollRepository;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Safe lifecycle preparation only. Pending policies cannot be approved by a boolean or a test adapter.
 * A BLOCKED result means the old employer still owns and pays for the retained staff. */
public final class BuildingLifecycleService {
    private final Connection connection;
    private final Object lock;
    private final SqliteBuildingRepository buildings;
    private final SqliteProductionExecutionRepository executions;
    private final SqliteProductionMethodConfigurationRepository methods;
    private final SqliteBuildingLifecycleOperationRepository operations;
    private final SqliteInventoryStore inventory;
    private final SqlitePayrollRepository payroll;
    private final Consumer<UUID> settleResources;
    private final Supplier<Connection> verificationConnection;

    public BuildingLifecycleService(Connection c, Object lock, ProductionRecipeResolver recipes,
                                    Consumer<UUID> settleResources, Supplier<Connection> verificationConnection) {
        connection=c;this.lock=lock;this.settleResources=settleResources;this.verificationConnection=verificationConnection;
        buildings=new SqliteBuildingRepository(c,lock);executions=new SqliteProductionExecutionRepository(c,lock);
        methods=new SqliteProductionMethodConfigurationRepository(c,recipes,lock);
        operations=new SqliteBuildingLifecycleOperationRepository(c,lock);inventory=new SqliteInventoryStore(c,lock);
        payroll=new SqlitePayrollRepository(c,lock);
    }
    public Optional<BuildingLifecycleOperation> find(UUID operation) {return operations.find(operation);}
    public Optional<BuildingLifecycleOperation> forBuilding(UUID building) {return operations.forBuilding(building);}
    /** Consistent committed planning facts. Reading a plan neither stops production nor approves policies. */
    public record Assessment(Command command,long acceptedTick,UUID payrollSegment,long wageRemainder,
                             long employees,boolean pendingPm,UUID batchId,ProductionBatch.Status batchStatus,
                             boolean inventoryConfigured,long inventoryUnits,int reservations,Set<Blocker> blockers) {
        public Assessment {blockers=Set.copyOf(blockers);}
        public boolean finalizationAvailable(){return false;}
    }
    public Assessment assess(Command command) {synchronized(lock) {return SqliteTransactions.read(connection,tx->{
        var b=buildings.inSnapshot(tx,command.buildingId());
        if(b.lifecycle()!=BuildingLifecycle.EXISTING||!b.ownerCompanyId().equals(command.seller())||b.identityRevision()!=command.expectedIdentityRevision())
            throw new IllegalStateException("Lifecycle identity/owner CAS conflict");
        if(command.buyer()!=null)try(var s=connection.prepareStatement("SELECT 1 FROM companies WHERE id=?")) {
            s.setString(1,command.buyer().toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Registered buyer CompanyId required");}
        }
        var e=executions.inSnapshot(tx,b.getId());boolean pending=methods.inSnapshot(tx,b.getId()).pending().isPresent();
        UUID segment;long fraction;
        try(var s=connection.prepareStatement("SELECT id,remainder FROM payroll_accruals WHERE building_id=? AND closed_tick IS NULL")) {
            s.setString(1,b.getId().toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException("Trusted payroll enrollment required");segment=UUID.fromString(r.getString(1));fraction=r.getLong(2);}
        }
        long employees=0;try(var s=connection.prepareStatement("SELECT employed FROM building_staffing_roles WHERE building_id=?")) {
            s.setString(1,b.getId().toString());try(var r=s.executeQuery()){while(r.next())employees=Math.addExact(employees,r.getLong(1));}
        }
        boolean configured;long units=0;int reservations=0;
        try(var s=connection.prepareStatement("SELECT state_json FROM inventory_locations WHERE location_id=?")) {
            s.setString(1,b.getId().toString());try(var r=s.executeQuery()) {
                configured=r.next();if(configured){var state=EconomyStateCodec.decodeInventory(b.getId(),r.getString(1)).state();for(long n:state.quantities().values())units=Math.addExact(units,n);reservations=state.reservations().size();}
            }
        }
        return new Assessment(command,SqliteEconomicTimeRepository.inSnapshot(connection,tx).acceptedTick(),segment,fraction,employees,pending,
                e.batch()==null?null:e.batch().getId(),e.batch()==null?null:e.batch().getStatus(),configured,units,reservations,BuildingLifecycleOperation.unresolvedPolicies(command.kind(),pending,configured,units>0));
    });}}

    /** Persist intent + automatic=false + irreversible Batch decision in one physical commit. */
    public BuildingLifecycleOperation request(Command command) {synchronized(lock) {
        write(command,tx->{
            var session=operations.inTransaction(tx);var prior=session.find(command.id());
            if(prior.isPresent()) {SqliteBuildingLifecycleOperationRepository.requireSameCommand(prior.get(),command);return null;}
            if(session.forBuilding(command.buildingId()).isPresent())throw new IllegalStateException("Building already has an unfinished lifecycle operation");
            var b=buildings.inTransaction(tx).load(command.buildingId());
            if(b.lifecycle()!=BuildingLifecycle.EXISTING||!b.ownerCompanyId().equals(command.seller())||b.identityRevision()!=command.expectedIdentityRevision())
                throw new IllegalStateException("Lifecycle identity/owner CAS conflict");
            if(command.buyer()!=null)try(var s=connection.prepareStatement("SELECT 1 FROM companies WHERE id=?")) {
                s.setString(1,command.buyer().toString());try(var r=s.executeQuery()) {if(!r.next())throw new IllegalArgumentException("Registered buyer CompanyId required");}
            }
            long tick=SqliteEconomicTimeRepository.inTransaction(connection,tx).acceptedTick();
            var segment=payroll.inTransaction(tx).currentForBuilding(b.getId());
            if(!segment.company().equals(b.ownerCompanyId())||!segment.region().equals(b.regionId()))throw new IllegalStateException("Trusted payroll ownership required");
            var repo=executions.inTransaction(tx);var e=repo.load(b.getId());e.terminate();
            var batch=e.batch();
            var outcome=batch==null?null:(batch.getStatus()==ProductionBatch.Status.SETTLING_COMPLETED||batch.getStatus()==ProductionBatch.Status.COMPLETED?ProductionSettlement.Outcome.COMPLETED:ProductionSettlement.Outcome.ABORTED);
            session.create(BuildingLifecycleOperation.begin(command,tick,segment.id(),batch==null?null:batch.getId(),outcome));
            repo.save(e,e.executionRevision());
            return null;
        });
        return recover(command.id());
    }}

    /** Restart may finish resource work; it cannot invent the missing final conveyance decision. */
    public BuildingLifecycleOperation recover(UUID operationId) {synchronized(lock) {
        var op=operations.find(operationId).orElseThrow(()->new IllegalArgumentException("Unknown lifecycle OperationId"));
        if(op.status()==Status.BLOCKED)return op;
        try {settleResources.accept(op.command().buildingId());}
        catch(CommitOutcomeUnknownException unknown) {
            SqliteConnectionGuard.quarantine(connection,unknown);
            throw new CommitOutcomeUnknownException("Lifecycle "+operationId+" resource outcome is unknown; verify using a fresh connection",unknown);
        }
        return write(op.command(),tx->{
            var session=operations.inTransaction(tx);var current=session.find(operationId).orElseThrow();
            if(current.status()==Status.BLOCKED)return current;
            UUID id=current.command().buildingId();var b=buildings.inTransaction(tx).load(id);
            if(b.lifecycle()!=BuildingLifecycle.EXISTING||!b.ownerCompanyId().equals(current.command().seller())||b.identityRevision()!=current.command().expectedIdentityRevision())
                throw new IllegalStateException("Lifecycle cannot publish a changed identity");
            var segment=payroll.inTransaction(tx).currentForBuilding(id);
            if(!segment.id().equals(current.payrollSegment()))throw new IllegalStateException("Lifecycle payroll source changed");
            try(var s=connection.prepareStatement("SELECT 1 FROM production_start_intents WHERE building_id=?")) {
                s.setString(1,id.toString());try(var r=s.executeQuery()){if(r.next())throw new IllegalStateException("StartIntent compensation remains unresolved");}
            }
            var repo=executions.inTransaction(tx);var e=repo.load(id);
            if(e.automatic()||e.hasUnfinishedBatch())throw new IllegalStateException("Lifecycle resource settlement remains unresolved");
            if(current.batchId()!=null) {
                var batch=e.batch()!=null?e.batch():e.lastBatch();
                if(batch==null||!batch.getId().equals(current.batchId())||!batch.isEnded())throw new IllegalStateException("Lifecycle Batch result cannot be confirmed");
                var expected=new ProductionSettlement(batch.getId(),current.settlement(),batch.getConfiguration().resolvedRecipe().inputs(),batch.getConfiguration().resolvedRecipe().outputs());
                var state=inventory.inspect(tx,id);
                if(!Objects.equals(state.settlements().get(batch.getId()),new com.bbmurloc.victoriaeconomics.server.inventory.domain.GoodsInventory.Settlement(expected)))
                    throw new IllegalStateException("Matching durable inventory settlement required");
            }
            boolean configured=inventory.configured(tx,id);
            boolean hasGoods=configured&&inventory.inspect(tx,id).quantities().values().stream().anyMatch(n->n>0);
            var reasons=BuildingLifecycleOperation.unresolvedPolicies(current.command().kind(),methods.inTransaction(tx).load(id).pending().isPresent(),configured,hasGoods);
            // The lifecycle intent replaces the production boundary, without applying any seller Pending.
            // Both the ended-Batch handoff and the retained BLOCKED fact share the physical commit.
            if(e.boundary()==ProductionExecution.Boundary.RECONFIGURING) {e.reconfigurationCompleted();repo.save(e,e.executionRevision());}
            var blocked=current.block(SqliteEconomicTimeRepository.inTransaction(connection,tx).acceptedTick(),reasons);
            session.save(blocked,current.revision());return blocked;
        });
    }}
    public void recoverUnfinished() {synchronized(lock) {
        for(var op:operations.unfinished())if(op.status()==Status.QUIESCING)recover(op.command().id());
    }}
    /** Always opens a new trusted connection and validates the entire persisted graph before returning facts. */
    public Optional<BuildingLifecycleOperation> verify(Command command) {synchronized(lock) {
        try(var fresh=verificationConnection.get()) {
            EconomySchema.validateExisting(fresh);
            return new SqliteBuildingLifecycleOperationRepository(fresh,new Object()).verify(command);
        }catch(SQLException failure) {throw new IllegalStateException("Cannot close trusted lifecycle verification connection",failure);}
    }}
    public Optional<BuildingLifecycleOperation> verify(UUID operationId,UUID buildingId) {synchronized(lock) {
        try(var fresh=verificationConnection.get()) {
            EconomySchema.validateExisting(fresh);
            return new SqliteBuildingLifecycleOperationRepository(fresh,new Object()).find(operationId).map(op->{
                if(!op.command().buildingId().equals(buildingId))throw new IllegalArgumentException("Operation belongs to another building");return op;
            });
        }catch(SQLException failure) {throw new IllegalStateException("Cannot close trusted lifecycle verification connection",failure);}
    }}
    private <T>T write(Command command,SqliteTransactions.WriteWork<T> work) {
        try {return SqliteTransactions.write(connection,work);}
        catch(CommitOutcomeUnknownException unknown) {
            SqliteConnectionGuard.quarantine(connection,unknown);
            throw new CommitOutcomeUnknownException("Lifecycle "+command.id()+" outcome is unknown; verify on a new trusted connection before retry",unknown);
        }
    }
}
