package com.bbmurloc.victoriaeconomics.server.payroll.domain;

import java.util.*;

/** No priority is invented between historical sources within a partially paid building. */
public final class PayrollPaymentPlanner {
    private PayrollPaymentPlanner() {}
    public static List<PayrollPaymentLine> allocate(List<PayrollPaymentLine> sources,long available) {
        var debts=new HashMap<UUID,Long>();var count=new HashMap<UUID,Integer>();
        var currency=sources.isEmpty()?null:sources.getFirst().currency();
        var identities=new HashSet<String>();
        for(var line:sources) {
            if(!line.currency().equals(currency)||!identities.add(line.segment()+":"+line.liabilityPeriod()))throw new IllegalArgumentException("Mixed currency or duplicate source");
            debts.merge(line.building(),line.debtBefore(),Math::addExact);count.merge(line.building(),1,Math::addExact);
        }
        var allocations=ProportionalPaymentAllocator.allocate(debts,available);var result=new ArrayList<PayrollPaymentLine>();
        for(var line:sources) {
            long group=allocations.get(line.building()),total=debts.get(line.building());
            if(group>0&&group<total&&count.get(line.building())>1)
                throw new PayrollPaymentUnavailable("UNRESOLVED_BUILDING_SOURCE_ALLOCATION: partial payment across multiple liabilities/segments requires an explicit policy");
            long amount=group==total?line.debtBefore():group;
            result.add(new PayrollPaymentLine(line.segment(),line.liabilityPeriod(),line.building(),line.region(),line.currency(),line.recipientAccount(),line.debtBefore(),amount));
        }
        return List.copyOf(result);
    }
}
