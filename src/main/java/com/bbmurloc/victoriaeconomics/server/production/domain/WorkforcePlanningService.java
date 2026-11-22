package com.bbmurloc.victoriaeconomics.server.production.domain;

import java.util.*;

/** 按本批招聘人数与满编需求计算精确速度。 */
public final class WorkforcePlanningService {
    public WorkforcePlan plan(Map<String,Integer> demand,Map<String,Integer> hired){
        if(demand.isEmpty())throw new IllegalArgumentException("Production requires positive occupation demand");
        hired.forEach((role,count)->{if(count==null||count<0||!demand.containsKey(role)||count>demand.get(role))throw new IllegalArgumentException("Invalid batch hire count");});
        int numerator=1,denominator=1;
        var participants=new TreeMap<String,Integer>();
        for(var entry:demand.entrySet()){
            int required=entry.getValue(),count=hired.getOrDefault(entry.getKey(),0);
            if(required<=0)throw new IllegalArgumentException("Positive occupation demand required");
            if(count==0)throw new IllegalStateException("No worker for required occupation: "+entry.getKey());
            participants.put(entry.getKey(),count);
            if(Math.multiplyExact((long)count,denominator)<Math.multiplyExact((long)numerator,required)){numerator=count;denominator=required;}
        }
        int gcd=gcd(numerator,denominator);
        return new WorkforcePlan(participants,numerator/gcd,denominator/gcd);
    }
    private static int gcd(int a,int b){while(b!=0){int next=a%b;a=b;b=next;}return a;}
}
