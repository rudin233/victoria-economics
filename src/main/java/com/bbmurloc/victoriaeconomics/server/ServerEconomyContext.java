package com.bbmurloc.victoriaeconomics.server;

import com.bbmurloc.victoriaeconomics.common.definition.DefaultDefinitions;
import com.bbmurloc.victoriaeconomics.common.definition.DefinitionValidator;
import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.good.GoodRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.industry.IndustryRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodGroupRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodRegistry;
import com.bbmurloc.victoriaeconomics.server.building.BuildingRegistry;
import com.bbmurloc.victoriaeconomics.server.building.BuildingRepository;
import com.bbmurloc.victoriaeconomics.server.building.BuildingService;
import com.bbmurloc.victoriaeconomics.server.production.ProductionRecipeResolver;
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

        DefaultDefinitions.registerIndustries(
                industryRegistry
        );

        DefaultDefinitions.registerGoods(
                goodRegistry
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
                buildingTypeRegistry,
                productionMethodGroupRegistry,
                productionMethodRegistry
        );

        this.buildingRegistry =
                new BuildingRegistry();

        this.buildingRepository =
                new SqliteBuildingRepository(database);

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
}
