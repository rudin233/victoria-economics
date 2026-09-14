package com.bbmurloc.victoriaeconomics.server;

import com.bbmurloc.victoriaeconomics.common.definition.DefaultDefinitions;
import com.bbmurloc.victoriaeconomics.common.definition.DefinitionValidator;
import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.good.GoodRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.industry.IndustryRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.occupation.OccupationRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodGroupRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodRegistry;
import com.bbmurloc.victoriaeconomics.server.building.BuildingRegistry;
import com.bbmurloc.victoriaeconomics.server.building.BuildingRepository;
import com.bbmurloc.victoriaeconomics.server.building.BuildingService;
import com.bbmurloc.victoriaeconomics.server.workforce.employment.EmploymentRegistry;
import com.bbmurloc.victoriaeconomics.server.workforce.employment.EmploymentService;
import com.bbmurloc.victoriaeconomics.server.production.calculation.ProductionRecipeResolver;
import com.bbmurloc.victoriaeconomics.server.production.ProductionService;
import com.bbmurloc.victoriaeconomics.server.workforce.staffing.StaffingCalculator;
import com.bbmurloc.victoriaeconomics.server.storage.EconomyDatabase;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteBuildingRepository;
import net.minecraft.server.MinecraftServer;

//正在运行的这个经济世界
public final class ServerEconomyContext implements AutoCloseable{

    private final EconomyDatabase database;
    private final GoodRegistry goodRegistry;
    private final IndustryRegistry industryRegistry;
    private final BuildingRegistry buildingRegistry;
    private final BuildingRepository buildingRepository;
    private final BuildingService buildingService;
    private final ProductionMethodRegistry productionMethodRegistry;
    private final ProductionMethodGroupRegistry productionMethodGroupRegistry;
    private final ProductionRecipeResolver productionRecipeResolver;
    private final OccupationRegistry occupationRegistry;
    private final EmploymentRegistry employmentRegistry;
    private final StaffingCalculator staffingCalculator;
    private final ProductionService productionService;
    private final EmploymentService employmentService;
    private final BuildingTypeRegistry buildingTypeRegistry;



    public ServerEconomyContext(
            MinecraftServer server
    ) {
        this.database =
                EconomyDatabase.open(server);

        this.industryRegistry =
                new IndustryRegistry();

        this.goodRegistry =
                new GoodRegistry();

        this.buildingTypeRegistry =
                new BuildingTypeRegistry();

        this.productionMethodRegistry =
                new ProductionMethodRegistry();

        this.productionMethodGroupRegistry =
                new ProductionMethodGroupRegistry();

        this.occupationRegistry =
                new OccupationRegistry();


        this.employmentRegistry =
                new EmploymentRegistry();


        DefaultDefinitions.registerIndustries(
                industryRegistry
        );

        DefaultDefinitions.registerGoods(
                goodRegistry
        );

        DefaultDefinitions.registerOccupations(
                occupationRegistry
        );

        DefaultDefinitions.registerProductionMethods(
                productionMethodRegistry
        );

        DefaultDefinitions.registerProductionMethodGroups(
                productionMethodGroupRegistry
        );

        DefaultDefinitions.registerBuildingTypes(
                buildingTypeRegistry
        );

        DefinitionValidator.validateAll(
                industryRegistry,
                goodRegistry,
                occupationRegistry,
                buildingTypeRegistry,
                productionMethodGroupRegistry,
                productionMethodRegistry
        );

        this.buildingRegistry =
                new BuildingRegistry();

        this.buildingRepository =
                new SqliteBuildingRepository(
                        database.getConnection()
                );

        this.buildingRepository
                .loadAll()
                .forEach(buildingRegistry::add);

        this.buildingService =
                new BuildingService(
                        buildingRegistry,
                        buildingRepository,
                        buildingTypeRegistry,
                        productionMethodGroupRegistry,
                        productionMethodRegistry
                );
        this.productionRecipeResolver =
                new ProductionRecipeResolver(
                        buildingTypeRegistry,
                        productionMethodGroupRegistry,
                        productionMethodRegistry
                );

        this.staffingCalculator =
                new StaffingCalculator(
                        employmentRegistry,
                        buildingTypeRegistry,
                        productionRecipeResolver
                );

        this.productionService =
                new ProductionService(
                        buildingRegistry,
                        productionRecipeResolver,
                        staffingCalculator,
                        employmentRegistry
                );

        this.employmentService =
                new EmploymentService(
                        employmentRegistry,
                        buildingRegistry,
                        buildingTypeRegistry,
                        occupationRegistry,
                        productionRecipeResolver
                );

    }

    public BuildingTypeRegistry getBuildingTypeRegistry() {
        return buildingTypeRegistry;
    }

    public BuildingRegistry getBuildingRegistry() {
        return buildingRegistry;
    }

    public BuildingService getBuildingService() {
        return buildingService;
    }

    @Override
    public void close() {
        database.close();
    }


    public GoodRegistry getGoodRegistry() {
        return goodRegistry;
    }

    public ProductionRecipeResolver getProductionRecipeResolver() {
        return productionRecipeResolver;
    }

    public OccupationRegistry getOccupationRegistry() {
        return occupationRegistry;
    }

    public EmploymentRegistry getEmploymentRegistry() {
        return employmentRegistry;
    }

    public EmploymentService getEmploymentService() {
        return employmentService;
    }

    public StaffingCalculator getStaffingCalculator() {
        return staffingCalculator;
    }

    public ProductionService getProductionService() {
        return productionService;
    }
}
