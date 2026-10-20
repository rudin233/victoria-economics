package com.bbmurloc.victoriaeconomics.server.production.domain;

import java.util.*;

/** Immutable integer participation; never a live Staffing authority. */
public record WorkforcePlan(Map<String,Integer> participatingWorkers,int speedNumerator,int speedDenominator) {
    public WorkforcePlan {
        var copy=new TreeMap<String,Integer>();
        participatingWorkers.forEach((role,count)->{
            if(role==null||role.isBlank()||count==null||count<=0)throw new IllegalArgumentException("Positive participation required");
            copy.put(role,count);
        });
        participatingWorkers=Map.copyOf(copy);
        if(copy.isEmpty()||speedNumerator<=0||speedDenominator<=0||speedNumerator>speedDenominator)throw new IllegalArgumentException("Invalid workforce speed");
    }
    public double speed(){return (double)speedNumerator/speedDenominator;}
}
