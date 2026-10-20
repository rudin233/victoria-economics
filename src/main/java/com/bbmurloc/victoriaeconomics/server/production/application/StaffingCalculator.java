package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import com.bbmurloc.victoriaeconomics.server.building.EconomicBuilding;
import com.bbmurloc.victoriaeconomics.server.building.department.hr.StaffingExpectation;
import com.bbmurloc.victoriaeconomics.server.workforce.employment.EmploymentRegistry;
import com.bbmurloc.victoriaeconomics.server.production.port.ProductionMethodConfigurationRepository;
import java.util.*;

/** Read-only HR diagnostics. Admission and qualified participant selection remain authoritative in ProductionService. */
public final class StaffingCalculator {
    public static final double MINIMUM_START_RATIO = 0.10;
    private static final double EPSILON = 1.0E-9;
    private final EmploymentRegistry employmentRegistry;
    private final BuildingTypeRegistry buildingTypeRegistry;
    private final ProductionMethodConfigurationRepository configurations;

    public StaffingCalculator(EmploymentRegistry employmentRegistry, BuildingTypeRegistry buildingTypeRegistry,
                              ProductionMethodConfigurationRepository configurations) {
        this.employmentRegistry = Objects.requireNonNull(employmentRegistry);
        this.buildingTypeRegistry = Objects.requireNonNull(buildingTypeRegistry);
        this.configurations = Objects.requireNonNull(configurations);
    }

    public StaffingSnapshot calculate(EconomicBuilding building) {
        return calculate(building, StaffingExpectation.fullStaffing());
    }

    public StaffingSnapshot calculate(EconomicBuilding building, StaffingExpectation expectation) {
        Objects.requireNonNull(building);
        Objects.requireNonNull(expectation);
        if (buildingTypeRegistry.get(building.getBuildingTypeId()) == null)
            throw new IllegalStateException("Unknown building type: " + building.getBuildingTypeId());
        var recipe = configurations.load(building.getId()).recipe();
        Map<String, OccupationStaffingSnapshot> snapshots = new HashMap<>();
        boolean canStart = !recipe.requiredWorkers().isEmpty();
        double minimum = 1.0;
        for (var entry : recipe.requiredWorkers().entrySet()) {
            int required = entry.getValue();
            if (required <= 0) throw new IllegalStateException("Resolved worker demand must be positive");
            double targetRatio = expectation.targetRatio(entry.getKey());
            // Legacy HR ratio targets remain query data until M2.2; they do not create participants.
            int target = targetRatio <= 0 ? 0 : Math.min(required, (int) Math.ceil(required * targetRatio - EPSILON));
            int employed = employmentRegistry.count(building.getId(), entry.getKey());
            int effective = Math.min(employed, required);
            double ratio = (double) effective / required;
            minimum = Math.min(minimum, ratio);
            if (10L * effective < required) canStart = false;
            snapshots.put(entry.getKey(), new OccupationStaffingSnapshot(entry.getKey(), required, targetRatio,
                    target, target, employed, effective, ratio));
        }
        return new StaffingSnapshot(snapshots, canStart, canStart ? minimum : 0.0);
    }
}
