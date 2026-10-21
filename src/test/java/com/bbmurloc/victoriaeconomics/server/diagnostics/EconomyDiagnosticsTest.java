package com.bbmurloc.victoriaeconomics.server.diagnostics;

import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class EconomyDiagnosticsTest {
    @Test void nestedStageCountersAreInclusiveAndCpuObservationAddsNoSql() throws Exception {
        try(var c=SqliteConnectionGuard.protect(DriverManager.getConnection("jdbc:sqlite::memory:"));var recording=EconomyDiagnostics.start()){
            try(var outer=EconomyDiagnostics.stage("outer")){
                SqliteTransactions.write(c,tx->{
                    try(var inner=EconomyDiagnostics.stage("inner")){
                        try(var s=c.createStatement();var r=s.executeQuery("SELECT 1")){assertTrue(r.next());}
                        EconomyDiagnostics.count("json.batch.decode",2);
                    }
                    return null;
                });
            }
            var counts=recording.snapshot().counts();
            assertEquals(1,counts.get("outer.sql.read"));assertEquals(1,counts.get("inner.sql.read"));
            assertEquals(1,counts.get("outer.tx.physical"));assertEquals(0,counts.get("inner.tx.physical"));
            assertEquals(2,counts.get("outer.json.batch.decode"));assertEquals(2,counts.get("inner.json.batch.decode"));
            assertEquals(1,counts.get("sql.read"));assertEquals(0,counts.get("outer.sql.dml"));
            assertTrue(c.getAutoCommit());
        }
    }
    @Test void countsReadWriteAndEmptyWriteScopesWithoutExtraSqlOrChangedOutcomes()throws Exception{
        try(var c=SqliteConnectionGuard.protect(DriverManager.getConnection("jdbc:sqlite::memory:"))){
            try(var s=c.createStatement()){s.execute("CREATE TABLE probe(value INTEGER)");}
            try(var recording=EconomyDiagnostics.start()){
                SqliteTransactions.write(c,tx->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM probe")){r.next();assertEquals(0,r.getLong(1));}return null;});
                SqliteTransactions.write(c,tx->{try(var s=c.prepareStatement("INSERT INTO probe VALUES(?)")){s.setLong(1,7);assertEquals(1,s.executeUpdate());}return null;});
                SqliteTransactions.read(c,tx->{try(var s=c.createStatement();var r=s.executeQuery("SELECT value FROM probe")){r.next();assertEquals(7,r.getLong(1));}return null;});
                var result=recording.snapshot();assertEquals(3,result.counts().get("tx.physical"));assertEquals(1,result.counts().get("tx.write.withDml"));
                assertEquals(1,result.counts().get("tx.write.withoutDml"));assertEquals(2,result.counts().get("sql.read"));assertEquals(1,result.counts().get("sql.dml"));
            }
            assertFalse(EconomyDiagnostics.enabled());
        }
    }
    @Test void recorderIsObservationalAndNeverAnAmbientTransactionCapability()throws Exception{
        try(var recording=EconomyDiagnostics.start();var pool=Executors.newSingleThreadExecutor()){
            pool.submit(()->{try(var ignored=EconomyDiagnostics.scope("otherThread")){EconomyDiagnostics.count("other");}}).get(5,TimeUnit.SECONDS);
            assertTrue(recording.snapshot().timings().isEmpty());assertTrue(recording.snapshot().counts().isEmpty());
            assertThrows(IllegalStateException.class,EconomyDiagnostics::start);
            try(var ignored=EconomyDiagnostics.scope("owner")){EconomyDiagnostics.count("observed");}
            assertEquals(1,recording.snapshot().timings().get("owner").count());recording.reset();assertTrue(recording.snapshot().counts().isEmpty());
        }
    }
    @Test void failureStillRollsBackAndItsDiagnosticScopeCannotPublishSuccess()throws Exception{
        try(var c=SqliteConnectionGuard.protect(DriverManager.getConnection("jdbc:sqlite::memory:"))){
            try(var s=c.createStatement()){s.execute("CREATE TABLE probe(value INTEGER)");}
            try(var recording=EconomyDiagnostics.start()){
                assertThrows(IllegalArgumentException.class,()->SqliteTransactions.write(c,tx->{try(var s=c.createStatement()){s.executeUpdate("INSERT INTO probe VALUES(1)");}throw new IllegalArgumentException("failed");}));
                try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM probe")){r.next();assertEquals(0,r.getInt(1));}
                assertTrue(c.getAutoCommit());assertEquals(1,recording.snapshot().timings().get("sqlite.transaction").count());
            }
        }
    }
    @Test void quantilesUseTheWholeRecordedSample() {
        var s=EconomyDiagnostics.summarize(List.of(1_000_000L,2_000_000L,3_000_000L,4_000_000L));
        assertEquals(4,s.count());assertEquals(2.5,s.meanMs());assertEquals(2,s.p50Ms());assertEquals(4,s.p95Ms());assertEquals(4,s.p99Ms());assertEquals(4,s.maxMs());
    }
}
