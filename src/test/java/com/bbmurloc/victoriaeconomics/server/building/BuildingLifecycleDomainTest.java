package com.bbmurloc.victoriaeconomics.server.building;

import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation;
import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation.*;
import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildingLifecycleDomainTest {
    final CompanyId seller=new CompanyId(UUID.randomUUID()),buyer=new CompanyId(UUID.randomUUID());
    Command command(Kind kind){return new Command(UUID.randomUUID(),UUID.randomUUID(),seller,kind==Kind.SALE?buyer:null,kind,0);}
    @Test void saleAndDemolitionHaveExplicitDifferentCompleteCommands(){
        var sale=command(Kind.SALE);assertNotEquals(sale,command(Kind.DEMOLITION));
        assertThrows(IllegalArgumentException.class,()->new Command(sale.id(),sale.buildingId(),seller,null,Kind.SALE,0));
        assertThrows(IllegalArgumentException.class,()->new Command(sale.id(),sale.buildingId(),seller,seller,Kind.SALE,0));
        assertThrows(IllegalArgumentException.class,()->new Command(sale.id(),sale.buildingId(),seller,buyer,Kind.DEMOLITION,0));
        assertThrows(IllegalArgumentException.class,()->new Command(sale.id(),sale.buildingId(),seller,buyer,Kind.SALE,-1));
    }
    @Test void quiescenceAndBlockingAreMonotonicAndDoNotClaimHandover(){
        var original=BuildingLifecycleOperation.begin(command(Kind.SALE),12,UUID.randomUUID(),null,null);
        var reasons=EnumSet.of(Blocker.WAGE_REMAINDER_POLICY_UNDECIDED);
        var blocked=original.block(13,reasons);reasons.clear();
        assertEquals(Status.QUIESCING,original.status());assertEquals(0,original.revision());assertNull(original.blockedTick());
        assertEquals(Status.BLOCKED,blocked.status());assertEquals(1,blocked.revision());assertEquals(Set.of(Blocker.WAGE_REMAINDER_POLICY_UNDECIDED),blocked.blockers());
        assertSame(blocked,blocked.block(14,Set.of()));assertThrows(UnsupportedOperationException.class,()->blocked.blockers().clear());
    }
    @Test void incompleteOrRewoundCheckpointsAreRejected(){
        var op=BuildingLifecycleOperation.begin(command(Kind.DEMOLITION),12,UUID.randomUUID(),null,null);
        assertThrows(IllegalArgumentException.class,()->op.block(11,Set.of(Blocker.WAGE_REMAINDER_POLICY_UNDECIDED)));
        assertThrows(IllegalArgumentException.class,()->op.block(12,Set.of()));
        assertThrows(IllegalArgumentException.class,()->BuildingLifecycleOperation.begin(op.command(),0,op.payrollSegment(),UUID.randomUUID(),null));
        assertThrows(IllegalArgumentException.class,()->new BuildingLifecycleOperation(op.command(),0,op.payrollSegment(),null,null,Status.QUIESCING,null,Set.of(),1));
    }
}
