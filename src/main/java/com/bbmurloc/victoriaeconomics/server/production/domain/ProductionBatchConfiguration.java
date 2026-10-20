package com.bbmurloc.victoriaeconomics.server.production.domain;

import java.util.*;

/** Frozen PM, recipe and participation. Wages and live employment remain outside this snapshot. */
public record ProductionBatchConfiguration(Map<String,String> productionMethodSelections,ResolvedProductionRecipe resolvedRecipe,WorkforcePlan workforcePlan,long effectiveRevision){
    public ProductionBatchConfiguration(Map<String,String> selections,ResolvedProductionRecipe recipe,WorkforcePlan plan){this(selections,recipe,plan,0);}
    public ProductionBatchConfiguration {
        if(effectiveRevision<0)throw new IllegalArgumentException("Negative effective revision");
        productionMethodSelections=Map.copyOf(productionMethodSelections);Objects.requireNonNull(resolvedRecipe);Objects.requireNonNull(workforcePlan);
        if(!resolvedRecipe.requiredWorkers().keySet().equals(workforcePlan.participatingWorkers().keySet()))throw new IllegalArgumentException("Workforce does not match recipe");
        var calculated=new WorkforcePlanningService().plan(resolvedRecipe.requiredWorkers(),workforcePlan.participatingWorkers());
        if(Math.multiplyExact((long)calculated.speedNumerator(),workforcePlan.speedDenominator())!=Math.multiplyExact((long)workforcePlan.speedNumerator(),calculated.speedDenominator()))throw new IllegalArgumentException("Workforce speed does not match participants");
    }
    public double productionSpeed(){return workforcePlan.speed();}
}
