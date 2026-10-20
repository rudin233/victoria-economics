package com.bbmurloc.victoriaeconomics.server.production.domain;

import java.util.*;

/** Exact speed from effective PM demand and authoritative employed counts. */
public final class WorkforcePlanningService {
    public WorkforcePlan plan(Map<String,Integer> demand,Map<String,Integer> employed){
        if(demand.isEmpty())throw new IllegalArgumentException("Production requires positive occupation demand");
        employed.forEach((role,count)->{if(count==null||count<0||count>demand.getOrDefault(role,0))throw new IllegalStateException("Employment exceeds effective PM capacity");});
        int numerator=1,denominator=1;
        var participants=new TreeMap<String,Integer>();
        for(var entry:demand.entrySet()){
            int required=entry.getValue(),count=employed.getOrDefault(entry.getKey(),0);
            if(required<=0)throw new IllegalArgumentException("Positive occupation demand required");
            if(Math.multiplyExact((long)count,10)<required)throw new IllegalStateException("Minimum 10% staffing not met: "+entry.getKey());
            participants.put(entry.getKey(),count);
            if(Math.multiplyExact((long)count,denominator)<Math.multiplyExact((long)numerator,required)){numerator=count;denominator=required;}
        }
        int gcd=gcd(numerator,denominator);
        return new WorkforcePlan(participants,numerator/gcd,denominator/gcd);
    }
    private static int gcd(int a,int b){while(b!=0){int next=a%b;a=b;b=next;}return a;}
}
