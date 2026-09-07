package com.bbmurloc.victoriaeconomics.server;

import com.bbmurloc.victoriaeconomics.server.building.BuildingRegistry;
import com.bbmurloc.victoriaeconomics.server.building.BuildingRepository;
import com.bbmurloc.victoriaeconomics.server.building.BuildingService;
import com.bbmurloc.victoriaeconomics.server.storage.EconomyDatabase;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteBuildingRepository;
import net.minecraft.server.MinecraftServer;

//正在运行的这个经济世界
public final class ServerEconomyContext implements AutoCloseable{

    private final EconomyDatabase database;
    private final BuildingRegistry buildingRegistry;
    private final BuildingRepository buildingRepository;
    private final BuildingService buildingService;

    public ServerEconomyContext(
            MinecraftServer server
    ) {
        this.database =
                EconomyDatabase.open(server);

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
                        buildingRepository
                );
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
}
