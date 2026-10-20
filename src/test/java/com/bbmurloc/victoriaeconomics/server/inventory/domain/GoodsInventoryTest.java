package com.bbmurloc.victoriaeconomics.server.inventory.domain;

import org.junit.jupiter.api.Test;
import java.util.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionSettlement;
import static com.bbmurloc.victoriaeconomics.server.production.domain.ProductionSettlement.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class GoodsInventoryTest {
    @Test
    void multipleMaterialsReserveAtomicallyAndCannotBeReused() {
        var stock = new GoodsInventory(UUID.randomUUID(), 100);
        stock.deposit(Map.of("wood", 20L, "iron", 5L));
        UUID batch = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> stock.reserve(batch, Map.of("wood", 20L, "iron", 6L)));
        assertTrue(stock.state().reservations().isEmpty());
        stock.reserve(batch, Map.of("wood", 20L, "iron", 5L));
        stock.reserve(batch, Map.of("wood", 20L, "iron", 5L));
        assertEquals(0, stock.available("wood"));
        assertThrows(IllegalStateException.class, () -> stock.withdraw(Map.of("wood", 1L)));
        assertThrows(IllegalStateException.class, () -> stock.reserve(UUID.randomUUID(), Map.of("iron", 1L)));
        stock.release(batch);
        stock.release(batch);
        assertEquals(20, stock.available("wood"));
    }

    @Test
    void completeAndAbortSettlementsAreExactlyOnceAndCannotChangeOutcome() {
        var stock = new GoodsInventory(UUID.randomUUID(), 100);
        stock.deposit(Map.of("wood", 40L));
        var inputs = Map.of("wood", 20L);
        var outputs = Map.of("tools", 30L);
        UUID first = UUID.randomUUID();
        stock.reserve(first, inputs);
        stock.settle(new ProductionSettlement(first, COMPLETED, inputs, outputs));
        stock.settle(new ProductionSettlement(first, COMPLETED, inputs, outputs));
        assertEquals(20, stock.quantity("wood"));
        assertEquals(30, stock.quantity("tools"));
        UUID second = UUID.randomUUID();
        stock.reserve(second, inputs);
        stock.settle(new ProductionSettlement(second, ABORTED, inputs, outputs));
        stock.settle(new ProductionSettlement(second, ABORTED, inputs, outputs));
        assertEquals(20, stock.quantity("wood"));
        assertEquals(30, stock.quantity("tools"));
        assertEquals(20, stock.available("wood"));
        assertTrue(stock.state().reservations().isEmpty());
        assertThrows(IllegalStateException.class, () -> stock.settle(new ProductionSettlement(second, COMPLETED, inputs, outputs)));
    }

    @Test
    void committedProductionMayFirstOverflowButNewIntakeAndStartsAreBlocked() {
        var stock = new GoodsInventory(UUID.randomUUID(), 20);
        stock.deposit(Map.of("wood", 20L));
        UUID batch = UUID.randomUUID();
        stock.reserve(batch, Map.of("wood", 10L));
        stock.settle(new ProductionSettlement(batch, COMPLETED, Map.of("wood", 10L), Map.of("tools", 30L)));
        assertTrue(stock.overCapacity());
        assertThrows(IllegalStateException.class, () -> stock.deposit(Map.of("iron", 1L)));
        assertThrows(IllegalStateException.class, () -> stock.reserve(UUID.randomUUID(), Map.of("wood", 1L)));
        stock.withdraw(Map.of("tools", 25L));
        assertFalse(stock.overCapacity());
        stock.deposit(Map.of("iron", 1L));
    }

    @Test void integerOverflowAndNonpositiveOperationsNeverPartiallyMutateGoods() {
        assertThrows(IllegalArgumentException.class, () -> new GoodsInventory(UUID.randomUUID(), -1));
        var stock = new GoodsInventory(UUID.randomUUID(), Long.MAX_VALUE);
        stock.deposit(Map.of("ore", Long.MAX_VALUE - 1, "metal", 1L));
        var before = stock.state();
        assertThrows(ArithmeticException.class, () -> stock.deposit(Map.of("ore", 2L)));
        assertThrows(ArithmeticException.class, () -> stock.deposit(Map.of("wood", 1L)));
        assertThrows(IllegalArgumentException.class, () -> stock.deposit(Map.of("ore", 0L)));
        assertThrows(IllegalArgumentException.class, () -> stock.withdraw(Map.of("ore", -1L)));
        assertThrows(IllegalArgumentException.class, () -> stock.reserve(UUID.randomUUID(), Map.of("ore", 0L)));
        assertEquals(before, stock.state());
        UUID batch = UUID.randomUUID();
        stock.reserve(batch, Map.of("ore", 1L));
        var reserved = stock.state();
        assertThrows(ArithmeticException.class, () -> stock.settle(new ProductionSettlement(batch, COMPLETED, Map.of("ore", 1L), Map.of("wood", 3L))));
        assertEquals(reserved, stock.state());
        assertNull(stock.settlement(batch));
    }

}
