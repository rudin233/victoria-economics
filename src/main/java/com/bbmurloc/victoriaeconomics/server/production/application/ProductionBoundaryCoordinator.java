package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.diagnostics.EconomyDiagnostics;

import com.bbmurloc.victoriaeconomics.common.definition.occupation.OccupationRegistry;
import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation;
import com.bbmurloc.victoriaeconomics.server.building.infrastructure.SqliteBuildingLifecycleOperationRepository;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.production.port.BuildingPayrollPort.State;
import com.bbmurloc.victoriaeconomics.server.production.port.ProductionExecutionRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import com.bbmurloc.victoriaeconomics.server.inventory.domain.GoodsInventory;
import com.bbmurloc.victoriaeconomics.server.payroll.domain.PayrollAccrual;
import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Small cross-root coordinator using the existing physical WriteSession; no nested transaction manager. */
public final class ProductionBoundaryCoordinator {
    private final Connection connection;
    private final Object lock;
    private final SqliteBuildingRepository buildings;
    private final SqliteProductionMethodConfigurationRepository methods;
    private final SqliteProductionExecutionRepository executions;
    private final SqliteBuildingStaffingRepository staffing;
    private final SqliteRegionalLaborSupplyRepository supply;
    private final SqlitePayrollRepository payroll;
    private final SqliteBuildingPayrollQuery wages;
    private final SqliteInventoryStore inventory;
    private final OccupationRegistry occupations;
    private final Supplier<Connection> verificationConnections;
    public ProductionBoundaryCoordinator(Connection c,ProductionRecipeResolver recipes,OccupationRegistry occupations,Object lock,
                                         Supplier<Connection> verificationConnections){
        connection=c;this.lock=lock;this.occupations=occupations;
        this.verificationConnections=Objects.requireNonNull(verificationConnections);
        buildings=new SqliteBuildingRepository(c,lock);methods=new SqliteProductionMethodConfigurationRepository(c,recipes,lock);
        executions=new SqliteProductionExecutionRepository(c,lock);staffing=new SqliteBuildingStaffingRepository(c,lock);
        supply=new SqliteRegionalLaborSupplyRepository(c,occupations,lock);payroll=new SqlitePayrollRepository(c,lock);
        wages=new SqliteBuildingPayrollQuery(c,lock);inventory=new SqliteInventoryStore(c,lock);
    }
    public ProductionExecution execution(UUID id){return executions.load(id);}
    public ProductionMethodConfiguration methods(UUID id){return methods.load(id);}
    public long acceptedTick(){return new SqliteEconomicTimeRepository(connection,lock).load().acceptedTick();}
    public List<UUID> buildingIds(){synchronized(lock){return buildings.loadAll().stream().filter(b->b.lifecycle()==BuildingLifecycle.EXISTING).map(EconomicBuilding::getId).toList();}}
    Map<RegionId, List<UUID>> buildingsByRegion() {
        synchronized (lock) {
            var regions = new TreeMap<RegionId, List<UUID>>(Comparator.comparing(RegionId::toString));
            buildings.loadAll().stream().filter(b -> b.lifecycle() == BuildingLifecycle.EXISTING)
                    .sorted(Comparator.comparing(EconomicBuilding::getId))
                    .forEach(b -> regions.computeIfAbsent(b.regionId(), ignored -> new ArrayList<>()).add(b.getId()));
            return regions;
        }
    }

    private <T>T write(SqliteTransactions.WriteWork<T> work){
        try (var diagnosticScope = EconomyDiagnostics.scope("production.coordinator.write")) {synchronized(lock){
        try{return SqliteTransactions.write(connection,work);}
        catch(CommitOutcomeUnknownException unknown){SqliteConnectionGuard.quarantine(connection,unknown);throw unknown;}
    }
        }
    }
    private EconomicBuilding live(SqliteTransactions.WriteSession tx,UUID id){
        var b=buildings.inTransaction(tx).load(id);if(b.lifecycle()!=BuildingLifecycle.EXISTING)throw new IllegalStateException("Historical building cannot operate");return b;
    }
    private EconomicBuilding admit(SqliteTransactions.WriteSession tx,UUID id)throws SQLException{
        var b=live(tx,id);SqliteBuildingLifecycleOperationRepository.requireOperational(connection,tx,id);return b;
    }
    public boolean lifecycleHeld(UUID id){return new SqliteBuildingLifecycleOperationRepository(connection,lock).forBuilding(id).isPresent();}
    public void changeMethods(UUID id,BiConsumer<ProductionMethodConfiguration,Boolean> change){write(tx->{
        admit(tx,id);var executionRepository=executions.inTransaction(tx);var e=executionRepository.load(id);var m=methods.inTransaction(tx).load(id);long revision=m.configurationRevision();
        change.accept(m,e.blocksConfiguration());
        if(m.configurationRevision()==revision)return null;
        if(e.blocksConfiguration())methods.inTransaction(tx).save(m,revision);
        else reconfigure(tx,id,e,m,revision,executionRepository);
        return null;
    });}
    public void reconfigure(UUID id) {
        try (var measurement = EconomyDiagnostics.stage("production.reconfigure")) {
            write(tx -> {
                var executionsInTransaction = executions.inTransaction(tx);
                var execution = executionsInTransaction.load(id);
                var method = methods.inTransaction(tx).load(id);
                reconfigure(tx, id, execution, method, method.configurationRevision(), executionsInTransaction);
                return null;
            });
        }
    }

    private void reconfigure(SqliteTransactions.WriteSession tx, UUID id, ProductionExecution execution,
                             ProductionMethodConfiguration method, long pmRevision,
                             ProductionExecutionRepository.Session repository) throws SQLException {
        var building = admit(tx, id);
        if (execution.hasUnfinishedBatch()) throw new IllegalStateException("Previous Batch unfinished");
        var segment = payrollSegment(tx, building);
        if (staffing.inTransaction(tx).load(id).batchId() != null || segment.employees() != 0)
            throw new IllegalStateException("Idle production must not retain batch labor");
        method.applyPending();
        if (method.configurationRevision() != pmRevision) {
            if (!payroll.inTransaction(tx).currencyForRegion(building.regionId()).equals(segment.currency()))
                throw new IllegalStateException("Regional wage currency mismatch");
            methods.inTransaction(tx).save(method, pmRevision);
        }
        if (execution.boundary() == ProductionExecution.Boundary.RECONFIGURING) {
            execution.reconfigurationCompleted();
            repository.save(execution, execution.executionRevision());
        }
    }

    private PayrollAccrual payrollSegment(SqliteTransactions.WriteSession tx, EconomicBuilding building) throws SQLException {
        var segment = payroll.inTransaction(tx).currentForBuilding(building.getId());
        if (!segment.company().equals(building.ownerCompanyId()) || !segment.region().equals(building.regionId()))
            throw new IllegalStateException("Payroll ownership does not match building");
        return segment;
    }

    public ProductionBatch startBatch(UUID id, boolean automatic) {
        synchronized (lock) {
            UUID batch = UUID.randomUUID();
            try (var measurement = EconomyDiagnostics.stage("production.commitStart")) {
                return write(tx -> start(tx, id, batch, automatic));
            } catch (CommitOutcomeUnknownException unknown) {
                throw verifyStartOutcome(id, batch, unknown);
            }
        }
    }

    private ProductionBatch start(SqliteTransactions.WriteSession tx, UUID id, UUID batch, boolean automatic) throws SQLException {
        var repository = executions.inTransaction(tx);
        var building = admit(tx, id);
        if (building.getStatus() != BuildingStatus.ACTIVE) throw new ProductionStartBlocked("Building stopped");
        var execution = repository.load(id);
        if (execution.batch() != null || execution.boundary() != ProductionExecution.Boundary.NONE)
            throw new ProductionStartBlocked("Previous Batch or reconfiguration unfinished");
        if (automatic && !execution.automatic()) throw new ProductionStartBlocked("Automatic production disabled");
        long tick = SqliteEconomicTimeRepository.inTransaction(connection, tx).acceptedTick();
        if (tick < execution.nextStartNotBeforeTick()) throw new ProductionStartBlocked("Wait until the next economic Tick");
        var staff = staffing.inTransaction(tx);
        var before = staff.load(id);
        var segment = payrollSegment(tx, building);
        if (before.batchId() != null || segment.employees() != 0)
            throw new IllegalStateException("Idle production must not retain batch labor");
        if (wages.inTransaction(tx, id, building.ownerCompanyId()) != State.CURRENT)
            throw new ProductionStartBlocked("Payroll is not CURRENT or is unavailable");
        var method = methods.inTransaction(tx).load(id);
        if (method.pending().isPresent()) throw new IllegalStateException("Pending PM not applied");
        var recipe = method.recipe();
        var stock = inventory.inspectStart(tx, id, recipe.inputs());
        if (!stock.configured()) throw new ProductionStartBlocked("Storage location has not been configured");
        if (stock.overCapacity()) throw new ProductionStartBlocked("Storage is over capacity");
        if (!stock.inputsAvailable()) throw new ProductionStartBlocked("Missing batch input");

        var regional = supply.inTransaction(tx);
        var hired = new TreeMap<String, Integer>();
        boolean missing = false;
        for (var requirement : recipe.requiredWorkers().entrySet()) {
            String occupation = requirement.getKey();
            if (occupations.get(occupation) == null) throw new IllegalStateException("Unknown recipe occupation: " + occupation);
            long total = regional.find(building.regionId(), occupation).map(s -> s.totalSupply()).orElse(0L);
            long occupied = regional.occupied(building.regionId(), occupation);
            if (occupied < 0 || occupied > total) throw new IllegalStateException("Invalid regional labor allocation");
            int count = Math.toIntExact(Math.min(requirement.getValue(), Math.subtractExact(total, occupied)));
            missing |= count == 0;
            hired.put(occupation, count);
        }
        if (missing) throw new ProductionStartBlocked("Regional labor unavailable for a required occupation");
        var workforce = new WorkforcePlanningService().plan(recipe.requiredWorkers(), hired);
        var config = new ProductionBatchConfiguration(method.effective().methods(), recipe, workforce, method.effectiveRevision());

        var wageRepository = payroll.inTransaction(tx);
        if (!wageRepository.currencyForRegion(building.regionId()).equals(segment.currency()))
            throw new IllegalStateException("Regional wage currency mismatch");
        inventory.reserve(tx, id, batch, recipe.inputs());
        wageRepository.accrue(segment, tick);
        if (execution.processingTick() == null && execution.lastCompletedTick() < tick) execution.beginTick(tick);
        var started = execution.start(batch, config, tick);
        if (!automatic) execution.requestAutomatic(true);
        repository.save(execution, execution.executionRevision());
        staff.acquire(id, batch, workforce.participatingWorkers(), before.revision());
        wageRepository.changeEmployees(segment.id(), staff.load(id).totalOccupied(), tick);
        return started;
    }

    private CommitOutcomeUnknownException verifyStartOutcome(UUID id, UUID batch, CommitOutcomeUnknownException unknown) {
        boolean committed;
        try (var fresh = verificationConnections.get()) {
            if (fresh == connection) throw new IllegalStateException("Independent start verification connection required");
            committed = SqliteTransactions.read(fresh, snapshot -> {
                EconomySchema.validateExisting(fresh, snapshot);
                var execution = new SqliteProductionExecutionRepository(fresh, new Object()).inSnapshot(snapshot, id);
                if (execution.batch() != null && batch.equals(execution.batch().getId())) {
                    if (!execution.batch().isActive()) throw new IllegalStateException("Unexpected verified start state");
                    return true;
                }
                try (var query = fresh.prepareStatement("SELECT 1 FROM production_batches WHERE batch_id=?")) {
                    query.setString(1, batch.toString());
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) throw new IllegalStateException("Attempted batch is not the current batch");
                    }
                }
                return false;
            });
        } catch (SQLException | RuntimeException unavailable) {
            var unverified = new CommitOutcomeUnknownException("Start outcome unverified for batch " + batch + "; runtime connection retired", unknown);
            unverified.addSuppressed(unavailable);
            return unverified;
        }
        // 核验只报告内存权威的结果。原连接保持隔离，禁止自动重试或确认 Tick。
        return new CommitOutcomeUnknownException("Start verified " + (committed ? "committed" : "not committed")
                + " for batch " + batch + "; runtime connection retired", unknown);
    }

    /** No detached aggregate escapes this decision; success means physical commit and cleanup were confirmed. */
    public enum TickResult { ALREADY_COMPLETED, COMPLETED, NEEDS_FOLLOW_UP }
    /** Keeps the explicit staged capability used by recovery/fault clients. */
    public boolean advance(UUID id,long tick){return write(tx->advance(tx,id,tick,false)!=TickResult.ALREADY_COMPLETED);}
    /**
     * Combine advance and completion only when all remaining work fits this building's current transaction.
     * Settlement, reconfiguration and automatic starts run in their existing follow-up phase.
     */
    public TickResult processTick(UUID id,long tick){try(var measurement=EconomyDiagnostics.stage("production.processTick")){return write(tx->advance(tx,id,tick,true));}}
    private TickResult advance(SqliteTransactions.WriteSession tx,UUID id,long tick,boolean completeSimple)throws SQLException{
        if(tick!=SqliteEconomicTimeRepository.inTransaction(connection,tx).acceptedTick())throw new IllegalArgumentException("Only accepted economic Tick may advance production");
        var b=live(tx,id);var executionRepository=executions.inTransaction(tx);var e=executionRepository.load(id);
        if (e.processingTick() == null && e.lastCompletedTick() >= tick) {
            // A lifecycle command can leave a durable decision after this building finished the Tick.
            return e.boundary() != ProductionExecution.Boundary.NONE || (e.batch() != null && e.batch().needsSettlement())
                    ? TickResult.NEEDS_FOLLOW_UP : TickResult.ALREADY_COMPLETED;
        }
        // An existing marker can represent a start born in this Tick or unfinished external work.
        if(!e.beginTick(tick))return TickResult.NEEDS_FOLLOW_UP;
        boolean held=SqliteBuildingLifecycleOperationRepository.holds(connection,tx,id);
        if(held)e.terminate();
        else{
            State state=wages.inTransaction(tx,id,b.ownerCompanyId());
            for(var reason:List.of(ProductionExecution.PauseReason.PAYROLL_ARREARS,ProductionExecution.PauseReason.PAYROLL_RECOVERY,ProductionExecution.PauseReason.PAYROLL_UNAVAILABLE))e.resume(reason);
            switch(state){
                case ARREARS->e.pause(ProductionExecution.PauseReason.PAYROLL_ARREARS);
                case RECOVERY->e.pause(ProductionExecution.PauseReason.PAYROLL_RECOVERY);
                case UNAVAILABLE->e.pause(ProductionExecution.PauseReason.PAYROLL_UNAVAILABLE);
                case CURRENT->{ }
            }
            try(var ignored=EconomyDiagnostics.scope("production.domain.advance")){e.tick(1);}
        }
        boolean finished=false;
        if(completeSimple&&e.boundary()==ProductionExecution.Boundary.NONE){
            if(!held&&e.batch()!=null&&!e.batch().needsSettlement()&&!e.batch().isEnded())finished=true;
            else if(e.batch()==null&&!e.automatic()){
                if(!held){
                    var m=methods.inTransaction(tx).load(id);
                    reconfigure(tx,id,e,m,m.configurationRevision(),executionRepository);
                    finished=true;
                }else{
                    var op=new SqliteBuildingLifecycleOperationRepository(connection,lock).inTransaction(tx).forBuilding(id).orElseThrow();
                    finished=op.status()==com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation.Status.BLOCKED;
                }
            }
        }
        if(finished)e.finishTick(tick);
        executionRepository.save(e,e.executionRevision());
        return finished?TickResult.COMPLETED:TickResult.NEEDS_FOLLOW_UP;
    }
    public void finishTick(UUID id,long tick){write(tx->{var repo=executions.inTransaction(tx);var e=repo.load(id);if(e.lastCompletedTick()==tick&&e.processingTick()==null)return null;e.finishTick(tick);repo.save(e,e.executionRevision());return null;});}
    void settle(UUID id, ProductionSettlement command) {
        synchronized (lock) {
            var attempt = new AtomicReference<SettlementAttempt>();
            try (var measurement = EconomyDiagnostics.stage("production.settlement")) {
                write(tx -> {
                    attempt.set(settle(tx, id, command));
                    return null;
                });
            } catch (CommitOutcomeUnknownException unknown) {
                throw verifySettlementOutcome(id, command, attempt.get(), unknown);
            }
        }
    }

    private record SettlementAttempt(long tick, long executionRevision, long staffingRevision,
                                     PayrollAccrual payrollBefore, PayrollAccrual payrollAfter,
                                     SqliteInventoryStore.SettlementBalances balances) {}

    private SettlementAttempt settle(SqliteTransactions.WriteSession tx, UUID id, ProductionSettlement command) throws SQLException {
        var building = buildings.inTransaction(tx).load(id);
        long tick = SqliteEconomicTimeRepository.inTransaction(connection, tx).acceptedTick();
        var repo = executions.inTransaction(tx);
        var execution = repo.load(id);
        UUID batchId = command.batchId();
        var batch = execution.batch();
        boolean current = batch != null && batchId.equals(batch.getId());
        if (!current) {
            var last = execution.lastBatch();
            batch = last != null && batchId.equals(last.getId()) ? last : executions.findBatch(tx, batchId);
        }
        if (batch == null || !id.equals(batch.getBuildingId()) || !ProductionSettlement.forBatch(batch).equals(command))
            throw new IllegalStateException("Foreign or mismatched settlement");

        var staff = staffing.inTransaction(tx);
        var allocation = staff.load(id);
        if (batch.isEnded()) {
            requireSettlementReceipt(inventory.inspect(tx, id), command);
            if (batch.endedTick() > tick || batchId.equals(allocation.batchId())
                    || ((current || building.lifecycle() != BuildingLifecycle.EXISTING) && allocation.batchId() != null))
                throw new IllegalStateException("Ended batch retains labor or has an invalid end Tick");
            if (building.lifecycle() == BuildingLifecycle.EXISTING) payroll.inTransaction(tx).validateEmployees(payrollSegment(tx, building));
            return null;
        }
        if (building.lifecycle() != BuildingLifecycle.EXISTING)
            throw new IllegalStateException("Historical building cannot settle a new batch");
        if (!current || !batchId.equals(allocation.batchId())
                || !allocation.occupiedByOccupation().equals(batch.getConfiguration().workforcePlan().participatingWorkers()))
            throw new IllegalStateException("Current batch labor does not match settlement");
        var wageRepository = payroll.inTransaction(tx);
        var segment = payrollSegment(tx, building);
        if (segment.employees() != allocation.totalOccupied())
            throw new IllegalStateException("Payroll headcount does not match batch labor");

        var operation = new SqliteBuildingLifecycleOperationRepository(connection, lock).inTransaction(tx).forBuilding(id);
        if (operation.isPresent()) {
            var op = operation.orElseThrow();
            if (op.status() != BuildingLifecycleOperation.Status.QUIESCING || !batchId.equals(op.batchId())
                    || command.outcome() != op.settlement() || !op.payrollSegment().equals(segment.id())
                    || !op.command().seller().equals(building.ownerCompanyId())
                    || op.command().expectedIdentityRevision() != building.identityRevision() || op.startedTick() > tick
                    || op.startedTick() < batch.startedTick() || op.startedTick() < segment.startTick()
                    || execution.automatic())
                throw new IllegalStateException("Settlement does not match lifecycle decision");
        } else if (command.outcome() == ProductionSettlement.Outcome.ABORTED) {
            throw new IllegalStateException("Durable lifecycle decision required to abort a batch");
        }

        var balances = inventory.settle(tx, id, command);
        if (balances.replayed()) throw new IllegalStateException("Unfinished batch already has an inventory settlement");
        wageRepository.accrue(segment, tick);
        staff.release(id, batchId, allocation.revision());
        var cutoff = wageRepository.changeEmployees(segment.id(), 0, tick);
        execution.settlementCompleted(batchId, tick);
        repo.save(execution, execution.executionRevision());
        return new SettlementAttempt(tick, execution.executionRevision(), allocation.revision(), segment, cutoff, balances);
    }

    private static void requireSettlementReceipt(GoodsInventory.State stock, ProductionSettlement command) {
        var receipt = stock.settlements().get(command.batchId());
        if (receipt == null || !receipt.command().equals(command) || stock.reservations().containsKey(command.batchId()))
            throw new IllegalStateException("Missing or conflicting durable settlement receipt");
    }

    private CommitOutcomeUnknownException verifySettlementOutcome(UUID id, ProductionSettlement command,
                                                                   SettlementAttempt attempt, CommitOutcomeUnknownException unknown) {
        if (attempt == null) return unknown; // No candidate commit completed staging.
        boolean committed;
        try (var fresh = verificationConnections.get()) {
            if (fresh == connection) throw new IllegalStateException("Independent settlement verification connection required");
            committed = SqliteTransactions.read(fresh, snapshot -> {
                EconomySchema.validateExisting(fresh, snapshot);
                var execution = new SqliteProductionExecutionRepository(fresh, lock).inSnapshot(snapshot, id);
                var batch = execution.batch();
                long tick = SqliteEconomicTimeRepository.inSnapshot(fresh, snapshot).acceptedTick();
                if (batch == null || !ProductionSettlement.forBatch(batch).equals(command) || tick != attempt.tick())
                    throw new IllegalStateException("Settlement batch or Tick changed before verification");
                boolean ended = batch.isEnded();
                var stock = new SqliteInventoryStore(fresh, lock).inSnapshot(snapshot, id);
                var allocation = new SqliteBuildingStaffingRepository(fresh, lock).inSnapshot(snapshot, id);
                var segment = new SqlitePayrollRepository(fresh, lock).inSnapshot(snapshot, attempt.payrollBefore().id());
                long executionRevision = attempt.executionRevision();
                long staffingRevision = attempt.staffingRevision();
                if (ended) {
                    requireSettlementReceipt(stock, command);
                    if (batch.endedTick() != tick || allocation.batchId() != null || !segment.equals(attempt.payrollAfter())
                            || !stock.quantities().equals(attempt.balances().after())
                            || execution.executionRevision() != Math.incrementExact(executionRevision)
                            || allocation.revision() != Math.incrementExact(staffingRevision))
                        throw new IllegalStateException("Committed settlement facts do not match");
                } else if (!command.inputs().equals(stock.reservations().get(command.batchId()))
                        || stock.settlements().containsKey(command.batchId()) || !stock.quantities().equals(attempt.balances().before())
                        || !segment.equals(attempt.payrollBefore()) || execution.executionRevision() != executionRevision
                        || allocation.revision() != staffingRevision) {
                    throw new IllegalStateException("Pre-settlement facts do not match");
                }
                return ended;
            });
        } catch (SQLException | RuntimeException unavailable) {
            var unverified = new CommitOutcomeUnknownException("Settlement outcome unverified for batch " + command.batchId()
                    + "; runtime connection retired", unknown);
            unverified.addSuppressed(unavailable);
            return unverified;
        }
        return new CommitOutcomeUnknownException("Settlement verified " + (committed ? "committed" : "not committed")
                + " for batch " + command.batchId() + "; runtime connection retired", unknown);
    }

    public void automatic(UUID id, boolean enabled) {
        write(tx -> {
            admit(tx, id);
            var repository = executions.inTransaction(tx);
            var execution = repository.load(id);
            if (!enabled && !execution.automatic() ) return null;
            execution.requestAutomatic(enabled);
            repository.save(execution, execution.executionRevision());
            return null;
        });
    }
    public void manualPause(UUID id,boolean pause){write(tx->{admit(tx,id);var e=executions.inTransaction(tx).load(id);if(pause)e.pause();else e.resume();executions.inTransaction(tx).save(e,e.executionRevision());return null;});}
    public void confirmWholeTick(long tick){write(tx->{
        var time=SqliteEconomicTimeRepository.inTransaction(connection,tx);if(time.acceptedTick()!=tick||!time.payrollCaughtUp())throw new IllegalStateException("Time/payroll not ready");
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT processed_period FROM payroll_payment_runtime WHERE id=1")){
            if(!r.next()||r.getLong(1)<time.periodId())throw new IllegalStateException("Payment cycle not confirmed");
        }
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT 1 FROM production_executions e JOIN economic_buildings identity ON identity.id=e.building_id LEFT JOIN production_batches b ON b.batch_id=e.current_batch_id WHERE identity.lifecycle='EXISTING' AND (e.last_completed_tick<>"+tick+" OR e.processing_tick IS NOT NULL OR e.boundary<>'NONE' OR b.status IN ('SETTLING_COMPLETED','SETTLING_ABORTED'))")){if(r.next())throw new IllegalStateException("Unfinished per-building production Tick");}
        if(time.firstUncertainProductionTick()!=null||time.productionCallbackReturnedTick()!=tick)
            try(var s=connection.prepareStatement("UPDATE economic_time SET first_uncertain_production_tick=NULL,production_callback_returned_tick=?,revision=? WHERE id=1 AND accepted_tick=? AND revision=?")){
                s.setLong(1,tick);s.setLong(2,Math.incrementExact(time.revision()));s.setLong(3,tick);s.setLong(4,time.revision());if(s.executeUpdate()!=1)throw new IllegalStateException("Economic time changed");
            }
        return null;
    });}
    public boolean needsRecovery(){
        try (var diagnosticScope = EconomyDiagnostics.scope("production.recoveryCheck")) {synchronized(lock){return SqliteTransactions.read(connection,tx->{
        var time=SqliteEconomicTimeRepository.inSnapshot(connection,tx);if(time.hasUnconfirmedProductionCallback())return true;
        try(var s=connection.prepareStatement("SELECT 1 FROM production_executions e JOIN economic_buildings identity ON identity.id=e.building_id LEFT JOIN production_batches b ON b.batch_id=e.current_batch_id WHERE identity.lifecycle='EXISTING' AND (e.processing_tick IS NOT NULL OR e.last_completed_tick<? OR e.boundary<>'NONE' OR b.status IN ('SETTLING_COMPLETED','SETTLING_ABORTED'))")){
            s.setLong(1,time.acceptedTick());try(var r=s.executeQuery()){return r.next();}
        }
    });}
        }
    }
}
