package com.bbmurloc.victoriaeconomics.server.payroll.domain;

import java.math.BigInteger;
import java.util.*;

/** Largest remainder between buildings only. Canonical UUID text is the stable tie breaker. */
public final class ProportionalPaymentAllocator {
    private ProportionalPaymentAllocator() {}
    public static Map<UUID,Long> allocate(Map<UUID,Long> debts,long available) {
        if(available<0)throw new IllegalArgumentException("Negative available cash");
        long total=0;
        for(var entry:debts.entrySet()) {
            Objects.requireNonNull(entry.getKey());
            if(entry.getValue()==null||entry.getValue()<=0)throw new IllegalArgumentException("Positive building debt required");
            total=Math.addExact(total,entry.getValue());
        }
        if(total==0)return Map.of();
        long budget=Math.min(total,available), assigned=0;
        var result=new HashMap<UUID,Long>();var remainders=new HashMap<UUID,BigInteger>();
        for(var entry:debts.entrySet()) {
            var parts=BigInteger.valueOf(budget).multiply(BigInteger.valueOf(entry.getValue())).divideAndRemainder(BigInteger.valueOf(total));
            long amount=parts[0].longValueExact();assigned=Math.addExact(assigned,amount);
            result.put(entry.getKey(),amount);remainders.put(entry.getKey(),parts[1]);
        }
        var order=new ArrayList<>(debts.keySet());
        order.sort(Comparator.<UUID,BigInteger>comparing(remainders::get).reversed().thenComparing(UUID::toString));
        long extra=Math.subtractExact(budget,assigned);
        for(int i=0;i<extra;i++)result.compute(order.get(i),(id,amount)->Math.incrementExact(amount));
        return Map.copyOf(result);
    }
}
