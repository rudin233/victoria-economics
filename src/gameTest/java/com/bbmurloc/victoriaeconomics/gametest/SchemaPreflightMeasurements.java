package com.bbmurloc.victoriaeconomics.gametest;

import com.bbmurloc.victoriaeconomics.server.diagnostics.EconomyDiagnostics;
import com.bbmurloc.victoriaeconomics.server.storage.EconomyDatabase;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Current formal checkpoint validation, memory restore and clean close; source bytes must not change. */
public final class SchemaPreflightMeasurements {
    public static void main(String[] args)throws Exception{
        Path build=Path.of(args[1]).toRealPath(),directory=Path.of(args[0]).toRealPath();
        if(!directory.startsWith(build)||!directory.getFileName().toString().startsWith("task-e-gametest-"))throw new IllegalArgumentException("Only owned isolated GameTest files are accepted");
        Path file=directory.resolve("world/victoria_economics/economy.sqlite");
        if(!Files.isRegularFile(file)){
            try(var files=Files.walk(directory,5)){file=files.filter(p->p.getFileName().toString().equals("economy.sqlite")).findFirst().orElseThrow();}
        }
        file=file.toRealPath();if(!file.startsWith(directory))throw new IllegalArgumentException("Database escaped the isolated directory");
        Path wal=Path.of(file+"-wal");byte[] before=hash(file),beforeWal=Files.exists(wal)?hash(wal):null;
        // First opening warms class loading separately; it still goes through the complete gate.
        Path worldRoot=file.getParent().getParent();
        try(var ignored=EconomyDatabase.open(worldRoot)){}
        EconomyDiagnostics.Snapshot result;
        try(var recording=EconomyDiagnostics.start()){
            for(int i=0;i<5;i++)try(var ignored=EconomyDatabase.open(worldRoot)){}
            result=recording.snapshot();
        }
        if(!Arrays.equals(before,hash(file))||!Arrays.equals(beforeWal,Files.exists(wal)?hash(wal):null))throw new IllegalStateException("Preflight modified database bytes");
        Files.writeString(directory.resolve("preflight-performance.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result)+System.lineSeparator());
        System.out.println(directory.getFileName()+": "+result.timings().get("runtime.open"));
    }
    private static byte[] hash(Path file)throws Exception{return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));}
}
