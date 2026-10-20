package com.bbmurloc.victoriaeconomics.server.production.domain;

import java.util.*;

/** Execution authority: immutable Batch, a reconfiguration barrier and per-building Tick evidence. */
public final class ProductionExecution {
    public enum Boundary {NONE,RECONFIGURING}
    public enum PauseReason {PAYROLL_ARREARS,PAYROLL_RECOVERY,PAYROLL_UNAVAILABLE,MANUAL}
    private final UUID buildingId;
    private long revision,lastCompletedTick;
    private Long processingTick;
    private ProductionBatch batch,lastBatch;
    private Boundary boundary=Boundary.NONE;
    private boolean automatic;
    private final EnumSet<PauseReason> pauses=EnumSet.noneOf(PauseReason.class);
    public ProductionExecution(UUID id){this(id,0);}
    public ProductionExecution(UUID id,long tick){buildingId=Objects.requireNonNull(id);if(tick<0)throw new IllegalArgumentException("Negative creation Tick");lastCompletedTick=tick;}
    public UUID buildingId(){return buildingId;}public long executionRevision(){return revision;}
    public ProductionBatch batch(){return batch;}public ProductionBatch lastBatch(){return lastBatch;}
    public Boundary boundary(){return boundary;}public boolean automatic(){return automatic;}
    public long lastCompletedTick(){return lastCompletedTick;}public Long processingTick(){return processingTick;}
    public Set<PauseReason> pauseReasons(){return Set.copyOf(pauses);}
    public boolean hasUnfinishedBatch(){return batch!=null&&!batch.isEnded();}
    public boolean blocksConfiguration(){return hasUnfinishedBatch()||boundary!=Boundary.NONE;}
    public void requestAutomatic(boolean enabled){automatic=enabled;}
    public void acknowledgeCommit(long expected){if(revision!=expected)throw new IllegalStateException("Unexpected execution revision");revision=Math.incrementExact(expected);}
    public static ProductionExecution rehydrate(UUID id,ProductionBatch batch,ProductionBatch last,Boundary boundary,boolean automatic,long revision){return rehydrate(id,batch,last,boundary,automatic,revision,0,null,Set.of());}
    public static ProductionExecution rehydrate(UUID id,ProductionBatch batch,ProductionBatch last,Boundary boundary,boolean automatic,long revision,long completed,Long processing,Set<PauseReason> reasons){
        var e=new ProductionExecution(id,completed);if(revision<0)throw new IllegalArgumentException("Negative revision");
        e.restore(batch,last,boundary,automatic);e.revision=revision;e.processingTick=processing;e.pauses.addAll(reasons);e.validate();return e;
    }
    public static final class State {
        private final ProductionExecution owner;private final ProductionBatch batch,last;private final Boundary boundary;private final boolean automatic;
        private final long revision,completed;private final Long processing;private final Set<PauseReason> pauses;
        private State(ProductionExecution e){owner=e;batch=e.batch;last=e.lastBatch;boundary=e.boundary;automatic=e.automatic;revision=e.revision;completed=e.lastCompletedTick;processing=e.processingTick;pauses=Set.copyOf(e.pauses);}
        public ProductionBatch batch(){return batch;}public ProductionBatch lastBatch(){return last;}public Boundary boundary(){return boundary;}public boolean automatic(){return automatic;}
    }
    public State state(){return new State(this);}
    public void restoreState(State s){if(s.owner!=this)throw new IllegalArgumentException("Foreign checkpoint");batch=s.batch;lastBatch=s.last;boundary=s.boundary;automatic=s.automatic;revision=s.revision;lastCompletedTick=s.completed;processingTick=s.processing;pauses.clear();pauses.addAll(s.pauses);validate();}
    /** Progress and this marker must share one physical commit. False means durable replay. */
    public boolean beginTick(long tick){
        if(processingTick!=null){if(processingTick==tick)return false;throw new IllegalStateException("Unfinished production Tick "+processingTick);}
        if(tick<=lastCompletedTick)return false;
        if(tick!=Math.incrementExact(lastCompletedTick))throw new IllegalStateException("Production Tick gap; do not guess catchup");
        processingTick=tick;return true;
    }
    public void finishTick(long tick){if(processingTick==null||processingTick!=tick)throw new IllegalStateException("No matching production Tick");lastCompletedTick=tick;processingTick=null;}
    public ProductionBatch start(UUID id,ProductionBatchConfiguration config){if(batch!=null||boundary!=Boundary.NONE)throw new IllegalStateException("Previous production has not finished");pauses.clear();batch=new ProductionBatch(id,buildingId,config,0,ProductionBatch.Status.ACTIVE);return batch;}
    public void tick(long ticks){
        if(ticks<0)throw new IllegalArgumentException("Negative economic ticks");if(batch==null||!batch.isActive())return;
        long remaining=Math.subtractExact(batch.getMaximumProgressUnits(),batch.getProgressUnits());int speed=batch.getConfiguration().workforcePlan().speedNumerator();
        long delta=ticks>remaining/speed?remaining:Math.min(remaining,Math.multiplyExact(ticks,speed));long progress=Math.addExact(batch.getProgressUnits(),delta);
        change(progress==batch.getMaximumProgressUnits()?ProductionBatch.Status.SETTLING_COMPLETED:ProductionBatch.Status.ACTIVE,progress);
    }
    public void pause(){pause(PauseReason.MANUAL);}public void resume(){resume(PauseReason.MANUAL);}
    public void pause(PauseReason reason){if(batch==null||batch.needsSettlement()||batch.isEnded())return;pauses.add(reason);change(ProductionBatch.Status.PAUSED,batch.getProgressUnits());}
    public void resume(PauseReason reason){pauses.remove(reason);if(batch!=null&&batch.getStatus()==ProductionBatch.Status.PAUSED&&pauses.isEmpty())change(ProductionBatch.Status.ACTIVE,batch.getProgressUnits());}
    public void terminate(){automatic=false;if(batch!=null&&!batch.isEnded()&&!batch.needsSettlement()){pauses.clear();change(ProductionBatch.Status.SETTLING_ABORTED,batch.getProgressUnits());}}
    public void settlementCompleted(UUID id){if(batch==null||!batch.getId().equals(id))throw new IllegalStateException("Wrong settlement owner");if(batch.isEnded())return;if(!batch.needsSettlement())throw new IllegalStateException("Settlement not decided");pauses.clear();change(batch.getStatus()==ProductionBatch.Status.SETTLING_COMPLETED?ProductionBatch.Status.COMPLETED:ProductionBatch.Status.ABORTED,batch.getProgressUnits());boundary=Boundary.RECONFIGURING;}
    public void reconfigurationCompleted(){if(boundary!=Boundary.RECONFIGURING||batch==null||!batch.isEnded())throw new IllegalStateException("No settled reconfiguration barrier");lastBatch=batch;batch=null;boundary=Boundary.NONE;}
    private void change(ProductionBatch.Status status,long progress){batch=new ProductionBatch(batch.getId(),buildingId,batch.getConfiguration(),progress,status);}
    public void restore(ProductionBatch batch,ProductionBatch last,Boundary boundary,boolean automatic){
        if(this.batch!=null||lastBatch!=null||this.boundary!=Boundary.NONE)throw new IllegalStateException("Execution already initialized");
        if(batch!=null&&!batch.getBuildingId().equals(buildingId))throw new IllegalArgumentException("Foreign Batch");
        if((batch!=null&&batch.isEnded())!=(boundary==Boundary.RECONFIGURING))throw new IllegalArgumentException("Invalid restored barrier");
        if(last!=null&&(!last.isEnded()||!last.getBuildingId().equals(buildingId)||(batch!=null&&batch.getId().equals(last.getId()))))throw new IllegalArgumentException("Invalid Batch history");
        this.batch=batch;lastBatch=last;this.boundary=Objects.requireNonNull(boundary);this.automatic=automatic;
    }
    private void validate(){
        if(processingTick!=null&&processingTick!=Math.incrementExact(lastCompletedTick))throw new IllegalArgumentException("Invalid Tick checkpoint");
        if((batch!=null&&batch.getStatus()==ProductionBatch.Status.PAUSED)!=!pauses.isEmpty())throw new IllegalArgumentException("Pause reasons do not match Batch");
    }
}
