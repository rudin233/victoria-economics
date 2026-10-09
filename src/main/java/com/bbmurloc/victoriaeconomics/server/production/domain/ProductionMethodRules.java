package com.bbmurloc.victoriaeconomics.server.production.domain;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.production.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Building-scoped immutable definitions, fixed for the server session.
 */
public final class ProductionMethodRules {
    private final BuildingTypeDefinition type;
    private final Map<String, ProductionMethodGroupDefinition> groups;
    private final Map<String, ProductionMethodDefinition> methods;

    public ProductionMethodRules(BuildingTypeDefinition type, Collection<ProductionMethodGroupDefinition> groups,
                                 Collection<ProductionMethodDefinition> methods) {
        this.type = Objects.requireNonNull(type);
        this.groups = groups.stream().collect(Collectors.toUnmodifiableMap(
                ProductionMethodGroupDefinition::id, Function.identity()));
        this.methods = methods.stream().collect(Collectors.toUnmodifiableMap(
                ProductionMethodDefinition::id, Function.identity()));
    }

    public Map<String, String> defaults() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String group : type.productionMethodGroupIds()) result.put(group, requireGroup(group).defaultMethodId());
        resolve(result);
        return Map.copyOf(result);
    }

    public ResolvedProductionRecipe resolve(Map<String, String> selections) {
        if (!selections.keySet().equals(new HashSet<>(type.productionMethodGroupIds()))) {
            throw new IllegalArgumentException("PM target must select exactly every building group");
        }
        int baseTier = requireMethod(type.baseProductionMethodGroupId(), selections.get(type.baseProductionMethodGroupId())).tier();
        Map<String, Double> inputs = new TreeMap<>();
        Map<String, Double> outputs = new TreeMap<>();
        Map<String, Integer> workers = new TreeMap<>();
        for (String group : type.productionMethodGroupIds()) {
            ProductionMethodDefinition method = requireMethod(group, selections.get(group));
            if (method.tier() > baseTier)
                throw new IllegalArgumentException("PM " + method.id() + " exceeds base tier " + baseTier);
            method.inputChanges().forEach((good, q) -> inputs.merge(good, q, Double::sum));
            method.outputChanges().forEach((good, q) -> outputs.merge(good, q, Double::sum));
            method.workerChanges().forEach((occupation, n) -> workers.merge(occupation, n, Math::addExact));
        }
        // Do not discard small real quantities or introduce business rounding.
        inputs.values().removeIf(q -> q == 0.0);
        outputs.values().removeIf(q -> q == 0.0);
        workers.values().removeIf(n -> n == 0);
        return new ResolvedProductionRecipe(inputs, outputs, workers);
    }

    private ProductionMethodGroupDefinition requireGroup(String id) {
        ProductionMethodGroupDefinition group = groups.get(id);
        if (group == null || !group.buildingTypeId().equals(type.id()))
            throw new IllegalArgumentException("Unknown or foreign PM group: " + id);
        return group;
    }

    private ProductionMethodDefinition requireMethod(String groupId, String methodId) {
        ProductionMethodGroupDefinition group = requireGroup(groupId);
        ProductionMethodDefinition method = methods.get(methodId);
        if (method == null || !group.methodIds().contains(methodId) || !method.groupId().equals(groupId)) {
            throw new IllegalArgumentException("Unknown or foreign PM: " + methodId + " in " + groupId);
        }
        return method;
    }
}
