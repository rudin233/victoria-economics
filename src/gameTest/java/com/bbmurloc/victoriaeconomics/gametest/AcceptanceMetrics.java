package com.bbmurloc.victoriaeconomics.gametest;

import java.nio.file.*;
import java.util.*;

/** Diagnostic wall time only; never used as an economic clock or business input. */
final class AcceptanceMetrics {
    private static final List<Long> samples=new ArrayList<>();
    private static boolean active;
    private static long start;
    static void begin(){samples.clear();active=true;}
    static void beforeTick(){if(active)start=System.nanoTime();}
    static void afterTick(){if(active&&start!=0)samples.add(System.nanoTime()-start);}
    static void finish(Path file,long acceptedTick,String sqliteSettings)throws Exception{
        active=false;var sorted=samples.stream().sorted().toList();
        if(sorted.isEmpty())throw new IllegalStateException("No actual server Tick observations");
        double mean=sorted.stream().mapToDouble(Long::doubleValue).average().orElseThrow()/1e6;
        var json="{\"serverTickSamples\":"+sorted.size()+",\"acceptedTick\":"+acceptedTick+",\"meanMs\":"+mean+",\"p50Ms\":"+percentile(sorted,.50)+",\"p95Ms\":"+percentile(sorted,.95)+",\"maxMs\":"+sorted.getLast()/1e6+",\"sqlite\":\""+sqliteSettings+"\",\"java\":\""+System.getProperty("java.version")+"\"}";
        Files.writeString(file,json+System.lineSeparator());Files.writeString(Path.of("acceptance-result.txt"),"PASSED full");System.out.println("VE isolated server performance: "+json);
    }
    private static double percentile(List<Long> sorted,double p){return sorted.get((int)Math.ceil(sorted.size()*p)-1)/1e6;}
}
