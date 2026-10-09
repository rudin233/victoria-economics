package com.bbmurloc.victoriaeconomics.server.production;

import com.bbmurloc.victoriaeconomics.server.inventory.GoodsInventory;
import com.bbmurloc.victoriaeconomics.server.inventory.equipment.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class InventoryAndEquipmentTest {
    @Test
    void multipleMaterialsReserveAtomicallyAndCannotBeReused() {
        var stock = new GoodsInventory(UUID.randomUUID(), 100);
        stock.deposit(Map.of("wood", 20.0, "iron", 5.0));
        UUID batch = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> stock.reserve(batch, Map.of("wood", 20.0, "iron", 6.0)));
        assertTrue(stock.state().reservations().isEmpty());
        stock.reserve(batch, Map.of("wood", 20.0, "iron", 5.0));
        stock.reserve(batch, Map.of("wood", 20.0, "iron", 5.0));
        assertEquals(0, stock.available("wood"));
        assertThrows(IllegalStateException.class, () -> stock.withdraw(Map.of("wood", 1.0)));
        assertThrows(IllegalStateException.class, () -> stock.reserve(UUID.randomUUID(), Map.of("iron", 1.0)));
        stock.release(batch);
        stock.release(batch);
        assertEquals(20, stock.available("wood"));
    }

    @Test
    void normalAndProportionalSettlementsAreExactlyOnce() {
        var stock = new GoodsInventory(UUID.randomUUID(), 100);
        stock.deposit(Map.of("wood", 40.0));
        var inputs = Map.of("wood", 20.0);
        var outputs = Map.of("tools", 30.0);
        UUID first = UUID.randomUUID();
        stock.reserve(first, inputs);
        stock.settle(first, inputs, outputs, 1.0);
        stock.settle(first, inputs, outputs, 1.0);
        assertEquals(20, stock.quantity("wood"));
        assertEquals(30, stock.quantity("tools"));
        UUID second = UUID.randomUUID();
        stock.reserve(second, inputs);
        stock.settle(second, inputs, outputs, 0.25);
        stock.settle(second, inputs, outputs, 0.25);
        assertEquals(15, stock.quantity("wood"));
        assertEquals(37.5, stock.quantity("tools"));
        assertEquals(15, stock.available("wood"));
        assertTrue(stock.state().reservations().isEmpty());
        assertThrows(IllegalStateException.class, () -> stock.settle(second, inputs, outputs, 1.0));
    }

    @Test
    void committedProductionMayFirstOverflowButNewIntakeAndStartsAreBlocked() {
        var stock = new GoodsInventory(UUID.randomUUID(), 20);
        stock.deposit(Map.of("wood", 20.0));
        UUID batch = UUID.randomUUID();
        stock.reserve(batch, Map.of("wood", 10.0));
        stock.settle(batch, Map.of("wood", 10.0), Map.of("tools", 30.0), 1);
        assertTrue(stock.overCapacity());
        assertThrows(IllegalStateException.class, () -> stock.deposit(Map.of("iron", 1.0)));
        assertThrows(IllegalStateException.class, () -> stock.reserve(UUID.randomUUID(), Map.of("wood", 1.0)));
        stock.withdraw(Map.of("tools", 25.0));
        assertFalse(stock.overCapacity());
        stock.deposit(Map.of("iron", 1.0));
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
