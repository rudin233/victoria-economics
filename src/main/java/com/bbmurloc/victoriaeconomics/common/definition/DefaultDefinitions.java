package com.bbmurloc.victoriaeconomics.common.definition;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.good.GoodCategory;
import com.bbmurloc.victoriaeconomics.common.definition.good.GoodDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.good.GoodRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.industry.IndustryDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.industry.IndustryRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.occupation.OccupationDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.occupation.OccupationRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.*;
import com.bbmurloc.victoriaeconomics.common.definition.productionequipment.ProductionEquipmentDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.productionequipment.ProductionEquipmentDefinitionRegistry;

import java.util.List;
import java.util.Map;

public final class DefaultDefinitions {

//    Industry
//    Good
//    ProductionMethod
//    ProductionMethodGroup
//    BuildingType
    private DefaultDefinitions() {
    }

    public static void registerIndustries(
            IndustryRegistry industryRegistry
    ) {
        industryRegistry.register(
                new IndustryDefinition("mining")
        );

        industryRegistry.register(
                new IndustryDefinition("logging")
        );

        industryRegistry.register(
                new IndustryDefinition("light_industries")
        );

        industryRegistry.register(
                new IndustryDefinition("plantations")
        );
    }

    public static void registerGoods(
            GoodRegistry goodRegistry
    ) {
        goodRegistry.register(
                new GoodDefinition(
                        "tools",
                        GoodCategory.INDUSTRIAL
                )
        );

        goodRegistry.register(
                new GoodDefinition(
                        "iron",
                        GoodCategory.INDUSTRIAL
                )
        );

        goodRegistry.register(
                new GoodDefinition(
                        "wood",
                        GoodCategory.STAPLE
                )
        );
    }


    public static void registerProductionMethods(
            ProductionMethodRegistry registry
    ) {
        registry.register(
                new ProductionMethodDefinition(
                        "crude_tools",
                        "tooling_workshop_base",
                        1,
                        Map.of(
                                "wood", 30.0
                        ),
                        Map.of(
                                "tools", 30.0
                        ),
                        Map.of(
                                "laborer", 10,
                                "machinist", 4
                        )
                )
        );

        registry.register(
                new ProductionMethodDefinition(
                        "pig_iron_tools",
                        "tooling_workshop_base",
                        2,
                        Map.of(
                                "wood", 30.0,
                                "iron", 20.0
                        ),
                        Map.of(
                                "tools", 60.0
                        ),
                        Map.of()
                )
        );

        registry.register(
                new ProductionMethodDefinition(
                        "hand_assembly",
                        "tooling_workshop_automation",
                        1,
                        Map.of(),
                        Map.of(),
                        Map.of()
                )
        );
    }

    public static void registerProductionMethodGroups(
            ProductionMethodGroupRegistry registry
    ) {
        registry.register(
                new ProductionMethodGroupDefinition(
                        "tooling_workshop_base",
                        "tooling_workshop",
                        List.of(
                                "crude_tools",
                                "pig_iron_tools"
                        )
                )
        );

        registry.register(
                new ProductionMethodGroupDefinition(
                        "tooling_workshop_automation",
                        "tooling_workshop",
                        List.of(
                                "hand_assembly"
                        )
                )
        );
    }

    public static void registerBuildingTypes(
            BuildingTypeRegistry buildingTypeRegistry
    ) {
        buildingTypeRegistry.register(
                new BuildingTypeDefinition(
                        "tooling_workshop",
                        "light_industries",
                        "tooling_workshop_equipment",
                        100,
                        List.of(
                                "tooling_workshop_base",
                                "tooling_workshop_automation"
                        )
                )
        );
    }


    public static void registerOccupations(
            OccupationRegistry registry
    ) {
        registry.register(
                new OccupationDefinition(
                        "laborer",
                        1.0
                )
        );

        registry.register(
                new OccupationDefinition(
                        "machinist",
                        1.5
                )
        );

        registry.register(
                new OccupationDefinition(
                        "engineer",
                        3.0
                )
        );
    }

    public static void registerProductionEquipmentDefinitions(
            ProductionEquipmentDefinitionRegistry registry
    ) {
        registry.register(
                new ProductionEquipmentDefinition(
                        "iron_mine_equipment"
                )
        );

        registry.register(
                new ProductionEquipmentDefinition(
                        "logging_camp_equipment"
                )
        );

        registry.register(
                new ProductionEquipmentDefinition(
                        "tooling_workshop_equipment"
                )
        );
    }
}