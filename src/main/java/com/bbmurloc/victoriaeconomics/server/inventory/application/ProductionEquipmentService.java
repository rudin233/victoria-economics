package com.bbmurloc.victoriaeconomics.server.inventory.application;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.inventory.domain.ProductionEquipmentHolding;
import com.bbmurloc.victoriaeconomics.server.inventory.domain.EquipmentConfigurationRequest;
import java.util.*;
import java.util.function.*;

/**
 * 生产设备的应用服务，协调建筑查询、设备领域行为、持久化和运行时索引更新。
 *
 * <p>设备的真实持有数量、统一容量、批次保护和请求状态由
 * {@link ProductionEquipmentHolding} 所属的库存上下文保护。
 * 本服务负责安排这些领域行为的执行顺序，不另存一份设备余额。
 *
 * <p>修改流程统一为：取得独立副本 → 执行领域行为 → 持久化 → 更新索引。
 * 所有入口与生产、PM 配置和人事操作共用经济写锁，避免并发检查与提交相互穿插。
 */
public final class ProductionEquipmentService {
    /**
     * 查询建筑身份，用于为尚无持有记录的建筑取得设备类型定义。
     */
    private final BuildingRegistry buildings;
    /**
     * 建筑类型提供固定设备类型和统一设备容量。
     */
    private final BuildingTypeRegistry types;
    /**
     * 已持久化设备聚合的运行时查询索引。
     */
    private final ProductionEquipmentRegistry holdings;
    /**
     * 保存一组设备聚合；涉及两栋建筑的调拨必须在同一事务中提交。
     */
    private final Consumer<List<ProductionEquipmentHolding>> save;
    /**
     * 与其他经济应用服务共享的写锁，不能为每个服务各建一把独立锁。
     */
    private final Object lock;

    /**
     * 装配设备应用服务。
     *
     * @param save 持久化回调，必须原子保存传入列表中的全部设备持有记录
     * @param lock 服务器经济上下文共享的写锁
     */
    public ProductionEquipmentService(BuildingRegistry buildings, BuildingTypeRegistry types, ProductionEquipmentRegistry holdings,
                                      Consumer<List<ProductionEquipmentHolding>> save, Object lock) {
        this.buildings = buildings;
        this.types = types;
        this.holdings = holdings;
        this.save = save;
        this.lock = lock;
    }

    /**
     * 返回建筑设备状态的独立副本，修改返回对象不会改变索引中的权威状态。
     *
     * <p>尚无持有记录时，按建筑定义构造零数量状态供查询；
     * 单纯查询不会写数据库，也不会把这份空状态注册到索引中。
     *
     * @param building 经济建筑 ID
     */
    public ProductionEquipmentHolding getHolding(UUID building) {
        synchronized (lock) {
            var holding = holdings.get(building);
            if (holding == null) {
                var b = buildings.get(building);
                if (b == null) throw new IllegalArgumentException("Unknown building: " + building);
                var type = types.get(b.getBuildingTypeId());
                holding = new ProductionEquipmentHolding(building, type.productionEquipmentTypeId(), type.maxProductionEquipment());
            }
            // 返回脱离索引的副本，防止调用方绕过本服务的持久化流程修改实际持有状态。
            return new ProductionEquipmentHolding(holding.state());
        }
    }

    /**
     * 单栋建筑的统一修改流程。
     *
     * <p>领域校验和修改都发生在副本上；只有保存成功才替换查询索引。
     * 校验或保存抛出异常时，索引仍保留本次操作前的对象。
     * 回调返回值仅在完成持久化后交给调用方。
     */
    private <T> T mutate(UUID building, Function<ProductionEquipmentHolding, T> action) {
        synchronized (lock) {
            ProductionEquipmentHolding next = getHolding(building);
            T result = action.apply(next);
            // 必须先保存再更新索引，避免其他查询看到尚未持久化的设备变化。
            save.accept(List.of(next));
            holdings.replace(next);
            return result;
        }
    }

    /**
     * 增加未安装设备；由设备聚合检查数量为正以及统一总容量。
     * 新增设备不会直接提升生产能力，只有安装后的数量才计入设备能力。
     */
    public ProductionEquipmentHolding addUninstalledEquipment(UUID building, int amount) {
        return mutate(building, h -> {
            h.addQuantity(amount);
            return new ProductionEquipmentHolding(h.state());
        });
    }

    /**
     * 增加设备，并在同一次持久化中提交对应数量的安装请求。
     *
     * <p>没有批次保护时立即安装；受批次保护时，新设备先作为未安装设备持有，
     * 安装请求锁定全部所需数量并等待批次边界，不提前改变本批生产能力。
     * 如果新增或请求受理失败，副本不会保存，新增数量也不会进入实际库存。
     */
    public ProductionEquipmentHolding addEquipmentAndAutoInstall(UUID building, int amount) {
        return mutate(building, h -> {
            h.addQuantity(amount);
            h.request(UUID.randomUUID(), EquipmentConfigurationRequest.Kind.INSTALL, amount);
            return new ProductionEquipmentHolding(h.state());
        });
    }

    /**
     * 将可用的未安装设备调拨到另一栋设备类型相同的建筑。
     *
     * <p>源建筑不能调出已安装设备或被安装请求锁定的设备；
     * 目标建筑必须有足够的统一总容量。
     * 两边都先在副本上完成领域校验，再一次性保存，防止只扣减来源而未增加目标。
     * 调拨不改变已安装数量，因此不需要解除活动批次的设备保护。
     */
    public void transferUninstalledEquipment(UUID source, UUID destination, int amount) {
        synchronized (lock) {
            if (source.equals(destination))
                throw new IllegalArgumentException("Equipment transfer locations must differ");
            var from = getHolding(source);
            var to = getHolding(destination);
            if (!from.getProductionEquipmentTypeId().equals(to.getProductionEquipmentTypeId()))
                throw new IllegalArgumentException("Equipment types differ");
            from.removeUninstalledQuantity(amount);
            to.addQuantity(amount);
            // 持久化回调必须使用同一事务保存两边；成功后才同步更新两个查询索引。
            save.accept(List.of(from, to));
            holdings.replace(from);
            holdings.replace(to);
        }
    }

    /**
     * 使用新请求 ID 提交安装请求。
     * 每次调用表示一个新控制请求；重试已有请求应使用带 request 参数的重载。
     */
    public EquipmentConfigurationRequest install(UUID building, int amount) {
        return install(building, UUID.randomUUID(), amount);
    }

    /**
     * 受理指定 ID 的安装请求，并返回当前请求状态。
     *
     * <p>受理时必须有全部所需的可用未安装设备，数量不足直接拒绝；
     * 延期请求会排他锁定这部分设备，但不预留未来安装空间。
     * 是否立即执行由设备聚合的批次保护状态决定。
     * 重复提交相同 ID 和参数返回原请求，不重复安装；同一 ID 的不同参数会被拒绝。
     */
    public EquipmentConfigurationRequest install(UUID building, UUID request, int amount) {
        return mutate(building, h -> h.request(request, EquipmentConfigurationRequest.Kind.INSTALL, amount));
    }

    /**
     * 使用新请求 ID 提交卸载请求。
     * 重试同一个请求时应保留原 ID，并使用下面的重载。
     */
    public EquipmentConfigurationRequest uninstall(UUID building, int amount) {
        return uninstall(building, UUID.randomUUID(), amount);
    }

    /**
     * 受理指定 ID 的卸载请求；受批次保护时按受理顺序等待执行。
     *
     * <p>执行时按实际已安装数量确定可卸载数量，允许合法的部分执行。
     * 安装和卸载只改变设备形态，不改变总持有数量；
     * 同一请求 ID 的重试规则与安装请求一致。
     */
    public EquipmentConfigurationRequest uninstall(UUID building, UUID request, int amount) {
        return mutate(building, h -> h.request(request, EquipmentConfigurationRequest.Kind.UNINSTALL, amount));
    }

    /**
     * 取消尚未执行的设备请求；取消安装请求同时释放其未安装设备锁定。
     *
     * @return 待执行请求取消成功或原请求已取消时为 true；
     * 请求已执行或部分执行时为 false，不能通过取消反转真实设备变化
     * @throws IllegalArgumentException 请求 ID 不存在
     */
    public boolean cancel(UUID building, UUID request) {
        return mutate(building, h -> h.cancel(request));
    }

    /**
     * 从实际持有中移除可用未安装设备，供出售或报废等上层业务调用。
     * 本方法只处理设备数量，不处理资金结算，也不能移除被安装请求锁定的设备。
     */
    public void removeUninstalledEquipment(UUID building, int amount) {
        mutate(building, h -> {
            h.removeUninstalledQuantity(amount);
            return null;
        });
    }

    /**
     * 为即将提交的批次建立设备保护，阻止安装或卸载改变本批的设备能力。
     * 保护的所有者是批次 ID；有其他批次保护或未处理配置请求时由聚合拒绝。
     * 暂停和等待结算期间继续保留这份保护。
     */
    public void protectForBatch(UUID building, UUID batch) {
        mutate(building, h -> {
            h.protectForBatch(batch);
            return null;
        });
    }

    /**
     * 释放指定批次的设备保护，主要用于开工未提交时的失败补偿。
     *
     * <p>保护已经释放时可以重复调用；仍持有保护时必须匹配批次所有者。
     * 此方法不执行延期请求；正常批次结束时由批次边界入口统一处理。
     */
    public void releaseBatchProtection(UUID building, UUID batch) {
        mutate(building, h -> {
            h.releaseBatchProtection(batch);
            return null;
        });
    }

    /**
     * 在批次真正结束后释放设备保护，并按统一受理顺序处理延期安装和卸载。
     *
     * <p>生产协调器应先完成原料/产出结算和待变更 PM 生效，再调用本阶段；
     * 活动、暂停或待结算批次仍会阻止执行。之后的人事再配置和下一批评估由协调器负责。
     * 释放保护、实际数量变化及请求结果在同一次持久化中保存，
     * 重试时不会再次执行已经执行、部分执行或已取消的请求。
     *
     * @param endedBatch 已完成结算的批次 ID，用于核验仍存在的设备保护所有者
     * @return 本次处理的待执行请求数，包含部分执行的请求，不代表设备变化数量
     */
    public int flushPendingOperationsForBuilding(UUID building, UUID endedBatch) {
        synchronized (lock) {
            if (buildings.get(building).getProductionDepartment().hasActiveBatch())
                throw new IllegalStateException("Batch has not settled");
            return mutate(building, h -> {
                h.releaseBatchProtection(endedBatch);
                return h.processRequests();
            });
        }
    }

    /**
     * 查询是否仍有待执行设备请求；已执行、部分执行和已取消的历史请求不计入。
     */
    public boolean hasPendingOperationsForBuilding(UUID building) {
        return getHolding(building).hasPendingRequests();
    }
}
