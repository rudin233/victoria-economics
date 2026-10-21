package com.bbmurloc.victoriaeconomics.server.building.domain;

import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionSettlement;
import java.util.*;

/** Durable stop intent, separate from both building identity and production control status.
 * Final conveyance is deliberately unavailable until the remaining economic policies are approved. */
public record BuildingLifecycleOperation(Command command, long startedTick, UUID payrollSegment,
                                         UUID batchId, ProductionSettlement.Outcome settlement,
                                         Status status, Long blockedTick, Set<Blocker> blockers, long revision) {
    public enum Kind { SALE, DEMOLITION }
    public enum Status { QUIESCING, BLOCKED }
    public enum Blocker {
        PENDING_PM_POLICY_UNDECIDED, WAGE_REMAINDER_POLICY_UNDECIDED,
        INVENTORY_OWNERSHIP_UNDEFINED, INVENTORY_DISPOSITION_UNDECIDED,
        SALE_CONSIDERATION_UNDECIDED, INVENTORY_NOT_EMPTY
    }
    public record Command(UUID id, UUID buildingId, CompanyId seller, CompanyId buyer,
                          Kind kind, long expectedIdentityRevision) {
        public Command {
            Objects.requireNonNull(id); Objects.requireNonNull(buildingId);
            Objects.requireNonNull(seller); Objects.requireNonNull(kind);
            if (expectedIdentityRevision < 0) throw new IllegalArgumentException("Negative identity revision");
            if (kind == Kind.SALE ? buyer == null || buyer.equals(seller) : buyer != null)
                throw new IllegalArgumentException("Sale requires a distinct registered buyer; demolition has no buyer");
        }
    }
    public BuildingLifecycleOperation {
        Objects.requireNonNull(command); Objects.requireNonNull(payrollSegment); Objects.requireNonNull(status);
        blockers = Set.copyOf(blockers);
        if (startedTick < 0 || (batchId == null) != (settlement == null))
            throw new IllegalArgumentException("Invalid lifecycle intent");
        if (status == Status.QUIESCING) {
            if (revision != 0 || blockedTick != null || !blockers.isEmpty())
                throw new IllegalArgumentException("Invalid quiescing checkpoint");
        } else if (revision != 1 || blockedTick == null || blockedTick < startedTick || blockers.isEmpty()) {
            throw new IllegalArgumentException("Blocked is a retained incomplete operation, not a completed handover");
        }
    }
    public static BuildingLifecycleOperation begin(Command command, long tick, UUID segment,
                                                   UUID batch, ProductionSettlement.Outcome outcome) {
        return new BuildingLifecycleOperation(command,tick,segment,batch,outcome,Status.QUIESCING,null,Set.of(),0);
    }
    public BuildingLifecycleOperation block(long tick, Set<Blocker> reasons) {
        if (status == Status.BLOCKED) return this;
        return new BuildingLifecycleOperation(command,startedTick,payrollSegment,batchId,settlement,
                Status.BLOCKED,tick,reasons,Math.incrementExact(revision));
    }
    /** Known missing policies, not defaults or an authorization token for final conveyance. */
    public static Set<Blocker> unresolvedPolicies(Kind kind,boolean pendingPm,boolean inventory,boolean hasGoods) {
        var reasons=EnumSet.of(Blocker.WAGE_REMAINDER_POLICY_UNDECIDED);
        if(kind==Kind.SALE) {
            reasons.add(Blocker.SALE_CONSIDERATION_UNDECIDED);reasons.add(Blocker.INVENTORY_DISPOSITION_UNDECIDED);
            if(pendingPm)reasons.add(Blocker.PENDING_PM_POLICY_UNDECIDED);
        }
        if(inventory)reasons.add(Blocker.INVENTORY_OWNERSHIP_UNDEFINED);
        if(hasGoods) {reasons.add(Blocker.INVENTORY_NOT_EMPTY);if(kind==Kind.DEMOLITION)reasons.add(Blocker.INVENTORY_DISPOSITION_UNDECIDED);}
        return Set.copyOf(reasons);
    }
}
