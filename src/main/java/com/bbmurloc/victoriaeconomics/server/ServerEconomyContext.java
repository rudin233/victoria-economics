package com.bbmurloc.victoriaeconomics.server;

import com.bbmurloc.victoriaeconomics.server.diagnostics.EconomyDiagnostics;

import com.bbmurloc.victoriaeconomics.common.definition.*;
import com.bbmurloc.victoriaeconomics.common.definition.building.*;
import com.bbmurloc.victoriaeconomics.common.definition.good.*;
import com.bbmurloc.victoriaeconomics.common.definition.industry.*;
import com.bbmurloc.victoriaeconomics.common.definition.occupation.*;
import com.bbmurloc.victoriaeconomics.common.definition.production.*;
import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.production.application.ProductionService;
import com.bbmurloc.victoriaeconomics.server.production.application.EconomicClock;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionRecipeResolver;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.production.application.ProductionBoundaryCoordinator;
import com.bbmurloc.victoriaeconomics.server.production.application.StaffingCalculator;
import com.bbmurloc.victoriaeconomics.server.storage.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteBuildingRepository;
import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.SqliteBuildingStaffingRepository;
import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.SqliteOccupationCatalog;
import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.SqliteStaffingQuery;
import com.bbmurloc.victoriaeconomics.server.workforce.application.StaffingApplicationService;
import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionJournal;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionMethodConfigurationRepository;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionExecutionRepository;
import com.bbmurloc.victoriaeconomics.server.production.application.ProductionBuildingInitializer;
import net.minecraft.server.MinecraftServer;
import com.bbmurloc.victoriaeconomics.server.finance.application.FinanceService;
import com.bbmurloc.victoriaeconomics.server.payroll.application.PayrollAccrualService;
import com.bbmurloc.victoriaeconomics.server.payroll.application.CompanyPayrollService;
import com.bbmurloc.victoriaeconomics.server.payroll.application.PayrollPeriodService;
import com.bbmurloc.victoriaeconomics.server.household.application.RegionalHouseholdService;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.SqliteBuildingPayrollQuery;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import com.bbmurloc.victoriaeconomics.server.time.domain.EconomicTime;
import java.util.UUID;

public final class ServerEconomyContext implements AutoCloseable {
    private final Object economyLock = new Object();
    private final EconomyDatabase database;
    private final GoodRegistry goods = new GoodRegistry();
    private final IndustryRegistry industries = new IndustryRegistry();
    private final BuildingTypeRegistry types = new BuildingTypeRegistry();
    private final ProductionMethodRegistry methods = new ProductionMethodRegistry();
    private final ProductionMethodGroupRegistry groups = new ProductionMethodGroupRegistry();
    private final OccupationRegistry occupations = new OccupationRegistry();
    private final BuildingRegistry buildings = new BuildingRegistry();
    private final BuildingService buildingService;
    private final ProductionRecipeResolver recipes;
    private final StaffingCalculator staffing;
    private final StaffingApplicationService staffingService;
    private final ProductionService production;
    private final SqliteInventoryStore inventory;
    private final EconomicClock clock;
    private final FinanceService finance;
    private final PayrollAccrualService payrollAccrual;
    private final SqliteBuildingPayrollQuery payroll;
    private final CompanyPayrollService payrollPayments;
    private final PayrollPeriodService payrollPeriods;
    private final RegionalHouseholdService households;
    private final com.bbmurloc.victoriaeconomics.server.building.application.BuildingLifecycleService lifecycle;
    private boolean stopping;
    private long nativeSaves, nativeAutomaticSaves, nativeFlushSaves, captureFailures;

    public ServerEconomyContext(MinecraftServer server) {
        DefaultDefinitions.registerIndustries(industries);
        DefaultDefinitions.registerGoods(goods);
        DefaultDefinitions.registerOccupations(occupations);
        DefaultDefinitions.registerProductionMethods(methods);
        DefaultDefinitions.registerProductionMethodGroups(groups);
        DefaultDefinitions.registerBuildingTypes(types);
        DefinitionValidator.validateAll(industries, goods, occupations, types, groups, methods);
        recipes = new ProductionRecipeResolver(types, groups, methods);
        database = EconomyDatabase.open(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
        try {
            var connection = database.getConnection();
            SqliteOccupationCatalog.register(connection, occupations);
            var repository = new SqliteBuildingRepository(connection, economyLock);
            var methodRepository = new SqliteProductionMethodConfigurationRepository(connection, recipes, economyLock);
            var executionRepository = new SqliteProductionExecutionRepository(connection, economyLock);
            repository.loadAll().forEach(buildings::add);
            buildings.getAll().forEach(b -> { methodRepository.load(b.getId()); executionRepository.load(b.getId()); });
            inventory = new SqliteInventoryStore(connection, economyLock);
            var staffingRepository = new SqliteBuildingStaffingRepository(connection, recipes, occupations, economyLock);
            var initializer = new ProductionBuildingInitializer(connection, economyLock, repository, methodRepository, executionRepository, recipes,
                    staffingRepository, database::openVerificationConnection);
            buildingService = new BuildingService(buildings, repository, types, initializer, economyLock);
            staffing = new StaffingCalculator(new SqliteStaffingQuery(connection, recipes, occupations, economyLock));
            var coordinator=new ProductionBoundaryCoordinator(connection,recipes,occupations,economyLock);
            production = new ProductionService(coordinator,inventory,new SqliteProductionJournal(connection),economyLock);
            staffingService=new StaffingApplicationService(coordinator,production::retry);
            finance = new FinanceService(connection, economyLock);
            payrollAccrual = new PayrollAccrualService(connection, economyLock);
            payroll = new SqliteBuildingPayrollQuery(connection, economyLock);
            payrollPayments=new CompanyPayrollService(connection,economyLock);
            payrollPeriods=new PayrollPeriodService(connection,economyLock);
            households=new RegionalHouseholdService(connection,economyLock);
            lifecycle=new com.bbmurloc.victoriaeconomics.server.building.application.BuildingLifecycleService(connection,economyLock,recipes,production::settleForLifecycle,database::openVerificationConnection);
            clock = new EconomicClock(new SqliteEconomicTimeRepository(connection, economyLock),
                    payrollAccrual::recoverAcceptedTime, production::onEconomicTick,()->{lifecycle.recoverUnfinished();production.recoverAcceptedTick();},payrollPeriods::recoverAcceptedTime);
        } catch (RuntimeException failure) {
            database.close();
            throw failure;
        }
    }

    public void onServerTick() {
        try (var diagnosticScope = EconomyDiagnostics.scope("context.tick")) {
        synchronized (economyLock) {
            if (stopping) return;
            clock.onServerTick();
        }

        }
    }

    public BuildingRegistry getBuildingRegistry() {
        return buildings;
    }

    public BuildingService getBuildingService() {
        return buildingService;
    }

    public GoodRegistry getGoodRegistry() {
        return goods;
    }

    public ProductionRecipeResolver getProductionRecipeResolver() {
        return recipes;
    }

    public OccupationRegistry getOccupationRegistry() {
        return occupations;
    }

    public StaffingCalculator getStaffingCalculator() {
        return staffing;
    }
    public StaffingApplicationService getStaffingService() { return staffingService; }

    public ProductionService getProductionService() {
        return production;
    }

    public SqliteInventoryStore getInventoryStore() {
        return inventory;
    }

    public EconomicTime getEconomicTime() { synchronized (economyLock) { return clock.persistentTime(); } }
    public FinanceService getFinanceService() { return finance; }
    public PayrollAccrualService getPayrollAccrualService() { return payrollAccrual; }
    public BuildingPayrollPort getPayrollQuery() { return payroll; }
    public CompanyPayrollService getPayrollPayments(){return payrollPayments;}
    public PayrollPeriodService getPayrollPeriods(){return payrollPeriods;}
    public RegionalHouseholdService getHouseholds(){return households;}
    public com.bbmurloc.victoriaeconomics.server.building.application.BuildingLifecycleService getLifecycleService(){return lifecycle;}

    /** Queue acceptance is not a successful save. Capture touches no unresolved business transaction. */
    public void onNativeSave() {
        synchronized (economyLock) {
            try { database.requestCheckpoint().whenComplete((receipt, failure) -> {
                if (failure != null) com.bbmurloc.victoriaeconomics.VictoriaEconomics.LOGGER.error("VE economic checkpoint FAILED; previous save retained", failure);
                else com.bbmurloc.victoriaeconomics.VictoriaEconomics.LOGGER.debug("VE economic checkpoint committed: {} at tick {}", receipt.checkpointId(), receipt.economicTick());
            }); } catch (RuntimeException failure) {
                captureFailures = Math.incrementExact(captureFailures); throw failure;
            }
        }
    }
    public void onNativeSave(boolean flush, boolean forced) {
        synchronized (economyLock) {
            nativeSaves = Math.incrementExact(nativeSaves);
            if (!forced && !stopping) nativeAutomaticSaves = Math.incrementExact(nativeAutomaticSaves);
            if (flush) nativeFlushSaves = Math.incrementExact(nativeFlushSaves);
            onNativeSave();
        }
    }
    public record NativeSaveStatus(long requests, long automaticRequests, long flushRequests, long rejectedCaptures) {}
    public NativeSaveStatus nativeSaveStatus() { return new NativeSaveStatus(nativeSaves, nativeAutomaticSaves, nativeFlushSaves, captureFailures); }
    public void awaitEconomicSave() {
        try (var ignored = EconomyDiagnostics.scope("checkpoint.wait")) {
            synchronized (economyLock) { database.awaitLatestCheckpoint(); }
        }
    }
    public void awaitNativeSave() {
        try { awaitEconomicSave(); }
        catch (RuntimeException failure) {
            if (!stopping) throw failure;
            com.bbmurloc.victoriaeconomics.VictoriaEconomics.LOGGER.error("VE shutdown checkpoint NOT confirmed; continue closing native world files", failure);
        }
    }
    public void flushEconomicSave() { onNativeSave(); awaitEconomicSave(); }
    public void beginStopping() {
        synchronized (economyLock) { stopping = true; recoverBeforeSave(); }
        flushEconomicSave();
    }
    private void recoverBeforeSave() { lifecycle.recoverUnfinished(); production.recoverAcceptedTick(); }
    public com.bbmurloc.victoriaeconomics.server.storage.EconomicCheckpointWriter.Status checkpointStatus() { return database.checkpointStatus(); }
    public java.util.List<com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteCheckpointStore.Timing> checkpointTimings() { return database.checkpointTimings(); }
    /** Infrastructure verification reads current RAM authority, never the older disk image. */
    public java.sql.Connection openRuntimeVerificationConnection() {
        synchronized (economyLock) { return database.openVerificationConnection(); }
    }

    @Override
    public void close() {
        synchronized (economyLock) {
            stopping = true;
            RuntimeException recovery = null;
            try { recoverBeforeSave(); } catch (RuntimeException failure) { recovery = failure; }
            try {
            database.close();
            } catch (RuntimeException failure) {
                if (recovery != null) failure.addSuppressed(recovery);
                throw failure;
            }
            if (recovery != null) throw recovery;
        }
    }
}
