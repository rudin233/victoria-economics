package com.bbmurloc.victoriaeconomics.common.definition.productionequipment;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class ProductionEquipmentDefinitionRegistry {

    private final Map<String, ProductionEquipmentDefinition> definitions =
            new HashMap<>();

    public void register(
            ProductionEquipmentDefinition definition
    ) {
        Objects.requireNonNull(
                definition,
                "definition cannot be null"
        );

        String typeId =
                definition.productionEquipmentTypeId();

        if (definitions.containsKey(typeId)) {
            throw new IllegalArgumentException(
                    "Duplicate production equipment definition: "
                            + typeId
            );
        }

        definitions.put(
                typeId,
                definition
        );
    }

    public ProductionEquipmentDefinition get(
            String productionEquipmentTypeId
    ) {
        return definitions.get(
                productionEquipmentTypeId
        );
    }

    public boolean contains(
            String productionEquipmentTypeId
    ) {
        return definitions.containsKey(
                productionEquipmentTypeId
        );
    }

    public Collection<ProductionEquipmentDefinition> getAll() {
        return definitions.values();
    }
}