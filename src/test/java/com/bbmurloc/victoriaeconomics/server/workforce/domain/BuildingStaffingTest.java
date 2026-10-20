package com.bbmurloc.victoriaeconomics.server.workforce.domain;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildingStaffingTest {
    private final UUID id = UUID.randomUUID();
    private final Map<String, Integer> capacity = Map.of("worker", 10, "engineer", 4);

    @Test void emptyZeroReplacementAndCancellationHaveDifferentMeanings() {
        var root = BuildingStaffing.empty(id);
        assertTrue(root.employed().isEmpty()); assertTrue(root.pending().isEmpty());
        var zero = root.requestTarget("worker", 0, capacity);
        assertEquals(Map.of("worker", 0), zero.pending());
        assertEquals(1, zero.revision());
        var positive = zero.requestTarget("worker", 8, capacity);
        assertEquals(Map.of("worker", 8), positive.pending());
        assertTrue(positive.cancel("worker").pending().isEmpty());
        assertEquals(positive, positive.requestTarget("worker", 8, capacity));
        assertEquals(root, root.cancel("worker"));
    }
    @Test void invalidCountsCapacityAndRolesAreRejected() {
        var root = BuildingStaffing.empty(id);
        assertThrows(IllegalArgumentException.class, () -> root.requestTarget("worker", -1, capacity));
        assertThrows(IllegalArgumentException.class, () -> root.requestTarget("worker", 11, capacity));
        assertThrows(IllegalArgumentException.class, () -> root.requestTarget("unknown", 0, capacity));
        assertThrows(IllegalArgumentException.class, () -> new BuildingStaffing(id, Map.of("worker", -1), Map.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new BuildingStaffing(id, Map.of(), Map.of("worker", -1), 0));
        assertThrows(IllegalArgumentException.class, () -> new BuildingStaffing(id, Map.of(), Map.of(), -1));
    }
    @Test void partialHiringConsumesPendingExactlyOnce() {
        var root = BuildingStaffing.empty(id).requestTarget("worker", 8, capacity);
        var plan = StaffingTransition.consumePending(root, capacity, Map.of("worker", 3L));
        var after = root.apply(plan);
        assertEquals(Map.of("worker", 3), plan.hired());
        assertEquals(Map.of("worker", 3), after.employed());
        assertTrue(after.pending().isEmpty());
        assertEquals(after.employed(), StaffingTransition.consumePending(after, capacity, Map.of("worker", 100L)).after().employed());
        assertThrows(IllegalArgumentException.class, () -> after.apply(plan));
    }
    @Test void noSupplyStillConsumesThisAttemptWithoutAnAutomaticRefillTask() {
        var root = BuildingStaffing.empty(id).requestTarget("worker", 10, capacity);
        var after = StaffingTransition.consumePending(root, capacity, Map.of()).after();
        assertTrue(after.employed().isEmpty()); assertTrue(after.pending().isEmpty());
    }
    @Test void explicitZeroPlansFullDismissalWithoutNegativeEmployment() {
        var root = new BuildingStaffing(id, Map.of("worker", 8), Map.of("worker", 0), 2);
        var transition = StaffingTransition.consumePending(root, capacity, Map.of());
        assertEquals(Map.of("worker", 8), transition.dismissed());
        assertTrue(transition.after().employed().isEmpty());
        assertTrue(transition.after().pending().isEmpty());
        assertEquals(8, root.employed().get("worker")); // Planning does not apply wages or mutate a live model.
    }
    @Test void pmReductionPlansDismissalAndClampsTargetsWithoutHiring() {
        var root = new BuildingStaffing(id, Map.of("worker", 8, "engineer", 3), Map.of("worker", 10, "engineer", 4), 0);
        var plan = StaffingTransition.forCapacity(root, Map.of("worker", 5));
        assertEquals(Map.of("worker", 5), plan.after().employed());
        assertEquals(Map.of("worker", 5, "engineer", 0), plan.after().pending());
        assertEquals(Map.of("worker", 3, "engineer", 3), plan.dismissed());
        assertTrue(plan.hired().isEmpty());
        var increase = StaffingTransition.forCapacity(plan.after(), capacity);
        assertEquals(plan.after().employed(), increase.after().employed());
        assertTrue(increase.hired().isEmpty());
    }
    @Test void requestedGapAndCapacityBoundHiringEvenWithHugeSupply() {
        var root = new BuildingStaffing(id, Map.of("worker", 2), Map.of("worker", 9), 0);
        var plan = StaffingTransition.consumePending(root, Map.of("worker", 5), Map.of("worker", Long.MAX_VALUE));
        assertEquals(5, plan.after().employed().get("worker"));
        assertEquals(3, plan.hired().get("worker"));
    }
    @Test void mapSnapshotsAreImmutableAndForeignTransitionsAreRejected() {
        var input = new HashMap<>(Map.of("worker", 2));
        var root = new BuildingStaffing(id, input, Map.of(), 0);
        input.clear(); assertEquals(2, root.employed().get("worker"));
        assertThrows(UnsupportedOperationException.class, () -> root.employed().clear());
        var foreign = BuildingStaffing.empty(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> root.apply(StaffingTransition.forCapacity(foreign, capacity)));
    }
    @Test void revisionsAndCountsNeverWrap() {
        var root = new BuildingStaffing(id, Map.of(), Map.of(), Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> root.requestTarget("worker", 1, capacity));
        assertThrows(ArithmeticException.class, () -> StaffingTransition.forCapacity(root, capacity));
        var maximum = BuildingStaffing.empty(id).requestTarget("worker", Integer.MAX_VALUE, Map.of("worker", Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, StaffingTransition.consumePending(maximum, Map.of("worker", Integer.MAX_VALUE), Map.of("worker", Long.MAX_VALUE)).after().employed().get("worker"));
        assertThrows(IllegalArgumentException.class, () -> StaffingTransition.consumePending(maximum, capacity, Map.of("worker", -1L)));
    }
}
