package com.bbmurloc.victoriaeconomics.server;

import com.bbmurloc.victoriaeconomics.server.building.BuildingRegistry;
import com.bbmurloc.victoriaeconomics.server.building.BuildingService;

//正在运行的这个经济世界
public final class ServerEconomyContext {

    private final BuildingRegistry buildingRegistry;
    private final BuildingService buildingService;

    public ServerEconomyContext() {
        this.buildingRegistry = new BuildingRegistry();
        this.buildingService = new BuildingService(buildingRegistry);
    }

    public BuildingRegistry getBuildingManager() {
        return buildingRegistry;
    }

    public BuildingService getBuildingService() {
        return buildingService;
    }
}
