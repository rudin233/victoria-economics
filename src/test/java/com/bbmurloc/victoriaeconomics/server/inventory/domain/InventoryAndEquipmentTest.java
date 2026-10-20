package com.bbmurloc.victoriaeconomics.server.inventory.domain;

import org.junit.jupiter.api.Test;
import java.util.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionSettlement;
import static com.bbmurloc.victoriaeconomics.server.production.domain.ProductionSettlement.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryAndEquipmentTest {
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

    @Test
    void equipmentTotalCapacityIncludesUninstalledAndDoesNotChangeOnConfiguration() {
        var equipment = new ProductionEquipmentHolding(UUID.randomUUID(), "machine", 100);
        equipment.addQuantity(100);
        assertThrows(IllegalStateException.class, () -> equipment.addQuantity(1));
        equipment.request(UUID.randomUUID(), EquipmentConfigurationRequest.Kind.INSTALL, 60);
        assertEquals(100, equipment.getTotalQuantity());
        equipment.request(UUID.randomUUID(), EquipmentConfigurationRequest.Kind.UNINSTALL, 20);
        assertEquals(40, equipment.getInstalledQuantity());
        assertEquals(60, equipment.getUninstalledQuantity());
    }

    @Test
    void delayedInstallsReserveAllAvailableEquipmentAndCancellationReleasesIt() {
        var equipment = new ProductionEquipmentHolding(UUID.randomUUID(), "machine", 100);
        equipment.addQuantity(100);
        equipment.request(UUID.randomUUID(), EquipmentConfigurationRequest.Kind.INSTALL, 60);
        UUID batch = UUID.randomUUID();
        equipment.protectForBatch(batch);
        UUID request = UUID.randomUUID();
        equipment.request(request, EquipmentConfigurationRequest.Kind.INSTALL, 30);
        assertEquals(30, equipment.getLockedUninstalledQuantity());
        assertThrows(IllegalStateException.class, () -> equipment.request(UUID.randomUUID(), EquipmentConfigurationRequest.Kind.INSTALL, 11));
        assertThrows(IllegalStateException.class, () -> equipment.removeUninstalledQuantity(11));
        assertThrows(IllegalStateException.class, equipment::processRequests);
        assertTrue(equipment.cancel(request));
        assertTrue(equipment.cancel(request));
        assertEquals(40, equipment.getAvailableUninstalledQuantity());
        equipment.releaseBatchProtection(batch);
        equipment.processRequests();
        assertEquals(60, equipment.getInstalledQuantity());
    }

    @Test
    void equipmentRequestsKeepAcceptanceOrderSupportPartialExecutionAndCannotUndo() {
        var equipment = new ProductionEquipmentHolding(UUID.randomUUID(), "machine", 100);
        equipment.addQuantity(100);
        equipment.request(UUID.randomUUID(), EquipmentConfigurationRequest.Kind.INSTALL, 80);
        UUID batch = UUID.randomUUID();
        equipment.protectForBatch(batch);
        UUID uninstall = UUID.randomUUID(), install = UUID.randomUUID(), last = UUID.randomUUID();
        equipment.request(uninstall, EquipmentConfigurationRequest.Kind.UNINSTALL, 70);
        equipment.request(install, EquipmentConfigurationRequest.Kind.INSTALL, 20);
        equipment.request(last, EquipmentConfigurationRequest.Kind.UNINSTALL, 50);
        assertEquals(80, equipment.getInstalledQuantity());
        var reloaded = new ProductionEquipmentHolding(equipment.state());
        reloaded.releaseBatchProtection(batch);
        assertEquals(3, reloaded.processRequests());
        assertEquals(30, reloaded.find(last).executed());
        assertEquals(EquipmentConfigurationRequest.Status.PARTIAL, reloaded.find(last).status());
        assertEquals(0, reloaded.getInstalledQuantity());
        assertEquals(0, reloaded.getLockedUninstalledQuantity());
        assertFalse(reloaded.cancel(install));
        assertEquals(0, reloaded.processRequests());
        assertEquals(100, reloaded.getTotalQuantity());
    }
}
