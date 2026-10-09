package com.bbmurloc.victoriaeconomics.server.inventory.domain;

import java.util.*;

/**
 * 库存上下文中的单栋建筑设备聚合，保护真实设备数量、批次保护和配置请求。
 *
 * <p>一栋建筑只持有一种设备。已安装与未安装数量共用统一容量，
 * 总持有数量由两者相加得到，安装和卸载不会改变总量。
 * 待执行安装请求的数量同时构成未安装设备的排他锁定，
 * 锁定量从请求状态派生，避免再维护一份可能失同步的余额。
 */
public final class ProductionEquipmentHolding {
    /**
     * 持久化及独立副本使用的状态快照；重新构造聚合时会校验数量和请求约束。
     */
    public record State(UUID buildingId, String type, int capacity, int installed, int uninstalled,
                        UUID protectedByBatch, List<EquipmentConfigurationRequest> requests) {
        public State {
            Objects.requireNonNull(buildingId);
            Objects.requireNonNull(type);
            requests = List.copyOf(requests);
        }
    }

    private final UUID buildingId;
    private final String type;
    private final int capacity;
    private int installed;
    private int uninstalled;
    /**
     * 正在占用设备能力的批次；非空时安装与卸载请求只能延期执行。
     */
    private UUID protectedByBatch;
    /**
     * 按受理顺序保留请求及结果，重复提交和边界重试依靠已有状态避免重复执行。
     */
    private final List<EquipmentConfigurationRequest> requests;

    public ProductionEquipmentHolding(UUID building, String type, int capacity) {
        this(new State(building, type, capacity, 0, 0, null, List.of()));
    }

    public ProductionEquipmentHolding(State state) {
        buildingId = state.buildingId();
        type = state.type();
        capacity = state.capacity();
        installed = state.installed();
        uninstalled = state.uninstalled();
        protectedByBatch = state.protectedByBatch();
        requests = new ArrayList<>(state.requests());
        if (capacity <= 0 || installed < 0 || uninstalled < 0 || (long) installed + uninstalled > capacity) {
            throw new IllegalArgumentException("Equipment total exceeds unified capacity");
        }
        Set<UUID> ids = new HashSet<>();
        long previous = 0;
        for (var request : requests) {
            if (!ids.add(request.id()) || request.sequence() <= previous)
                throw new IllegalArgumentException("Invalid equipment request order");
            previous = request.sequence();
        }
        if (getLockedUninstalledQuantity() > uninstalled)
            throw new IllegalArgumentException("Equipment reservations exceed stock");
    }

    public State state() {
        return new State(buildingId, type, capacity, installed, uninstalled, protectedByBatch, requests);
    }

    public UUID getBuildingId() {
        return buildingId;
    }

    public String getProductionEquipmentTypeId() {
        return type;
    }

    public int getCapacity() {
        return capacity;
    }

    /**
     * 总量始终派生，不提供独立修改总量的入口。
     */
    public int getTotalQuantity() {
        return installed + uninstalled;
    }

    public int getInstalledQuantity() {
        return installed;
    }

    public int getUninstalledQuantity() {
        return uninstalled;
    }

    /**
     * 只有待执行安装请求锁定未安装设备；取消或执行后会自然退出锁定量统计。
     */
    public int getLockedUninstalledQuantity() {
        return Math.toIntExact(requests.stream().filter(r -> r.status() == EquipmentConfigurationRequest.Status.PENDING &&
                r.kind() == EquipmentConfigurationRequest.Kind.INSTALL).mapToLong(EquipmentConfigurationRequest::amount).sum());
    }

    /**
     * 调拨、移除和新安装请求只能使用扣除排他锁定后的未安装数量。
     */
    public int getAvailableUninstalledQuantity() {
        return uninstalled - getLockedUninstalledQuantity();
    }

    public boolean hasPendingRequests() {
        return requests.stream().anyMatch(r -> r.status() == EquipmentConfigurationRequest.Status.PENDING);
    }

    public void addQuantity(int amount) {
        positive(amount);
        if ((long) getTotalQuantity() + amount > capacity)
            throw new IllegalStateException("Unified equipment capacity exceeded");
        uninstalled += amount;
    }

    public void removeUninstalledQuantity(int amount) {
        positive(amount);
        if (amount > getAvailableUninstalledQuantity())
            throw new IllegalStateException("Uninstalled equipment is unavailable or reserved");
        uninstalled -= amount;
    }

    public void protectForBatch(UUID batch) {
        Objects.requireNonNull(batch);
        if (protectedByBatch != null && !protectedByBatch.equals(batch))
            throw new IllegalStateException("Equipment protected by another batch");
        if (hasPendingRequests()) throw new IllegalStateException("Pending equipment reconfiguration");
        protectedByBatch = batch;
    }

    public void releaseBatchProtection(UUID batch) {
        if (protectedByBatch == null) return;
        if (!protectedByBatch.equals(batch)) throw new IllegalStateException("Wrong equipment protection owner");
        protectedByBatch = null;
    }

    /**
     * 受理一个配置请求，先检查请求身份和材料可用性，再记录受理顺序。
     * 没有批次保护时立即处理；有保护时保留待执行状态。
     */
    public EquipmentConfigurationRequest request(UUID id, EquipmentConfigurationRequest.Kind kind, int amount) {
        positive(amount);
        Objects.requireNonNull(id);
        Objects.requireNonNull(kind);
        for (var prior : requests) {
            if (prior.id().equals(id)) {
                if (prior.kind() != kind || prior.amount() != amount)
                    throw new IllegalStateException("Equipment request id reused");
                return prior;
            }
        }
        // 安装必须一次取得全部可用未安装设备；受理时不预留未来安装空间。
        if (kind == EquipmentConfigurationRequest.Kind.INSTALL && amount > getAvailableUninstalledQuantity()) {
            throw new IllegalStateException("Insufficient available uninstalled equipment");
        }
        long sequence = requests.isEmpty() ? 1 : Math.addExact(requests.getLast().sequence(), 1);
        var accepted = new EquipmentConfigurationRequest(id, sequence, kind, amount, 0, EquipmentConfigurationRequest.Status.PENDING);
        requests.add(accepted);
        if (protectedByBatch == null) processRequests();
        return find(id);
    }

    /**
     * 取消待执行请求；已取消可重复确认，已执行或部分执行不能通过取消回滚。
     */
    public boolean cancel(UUID id) {
        for (int i = 0; i < requests.size(); i++) {
            var request = requests.get(i);
            if (request.id().equals(id)) {
                if (request.status() == EquipmentConfigurationRequest.Status.CANCELLED) return true;
                if (request.status() != EquipmentConfigurationRequest.Status.PENDING) return false;
                requests.set(i, new EquipmentConfigurationRequest(id, request.sequence(), request.kind(), request.amount(), 0,
                        EquipmentConfigurationRequest.Status.CANCELLED));
                return true;
            }
        }
        throw new IllegalArgumentException("Unknown equipment request: " + id);
    }

    /**
     * 解除批次保护后，按受理顺序处理所有待执行请求。
     * 每个请求根据执行时的真实数量决定执行量，并保留完整或部分执行结果。
     */
    public int processRequests() {
        if (protectedByBatch != null) throw new IllegalStateException("Cannot reconfigure protected batch equipment");
        int processed = 0;
        for (int i = 0; i < requests.size(); i++) {
            var request = requests.get(i);
            if (request.status() != EquipmentConfigurationRequest.Status.PENDING) continue;
            int actual;
            if (request.kind() == EquipmentConfigurationRequest.Kind.INSTALL) {
                actual = Math.min(request.amount(), Math.min(uninstalled, capacity - installed));
                uninstalled -= actual;
                installed += actual;
            } else {
                actual = Math.min(request.amount(), installed);
                installed -= actual;
                uninstalled += actual;
            }
            // 请求离开待执行状态后，未执行的安装余量也退出锁定量统计，避免遗留设备锁。
            requests.set(i, new EquipmentConfigurationRequest(request.id(), request.sequence(), request.kind(), request.amount(), actual,
                    actual == request.amount() ? EquipmentConfigurationRequest.Status.EXECUTED : EquipmentConfigurationRequest.Status.PARTIAL));
            processed++;
        }
        return processed;
    }

    public EquipmentConfigurationRequest find(UUID id) {
        return requests.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown equipment request"));
    }

    private static void positive(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("Equipment amount must be positive");
    }
}
