package com.bbmurloc.victoriaeconomics.server.productionequipment;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import com.bbmurloc.victoriaeconomics.server.building.BuildingRegistry;
import com.bbmurloc.victoriaeconomics.server.building.EconomicBuilding;
import com.bbmurloc.victoriaeconomics.server.productionequipment.operation.ProductionEquipmentOperation;
import com.bbmurloc.victoriaeconomics.server.productionequipment.operation.ProductionEquipmentOperationQueue;
import com.bbmurloc.victoriaeconomics.server.productionequipment.operation.ProductionEquipmentOperationSubmission;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ProductionEquipmentService {

    private final BuildingRegistry buildingRegistry;
    private final BuildingTypeRegistry buildingTypeRegistry;
    private final ProductionEquipmentRegistry productionEquipmentRegistry;
    private final ProductionEquipmentOperationQueue operationQueue;


    public ProductionEquipmentService(
            BuildingRegistry buildingRegistry,
            BuildingTypeRegistry buildingTypeRegistry,
            ProductionEquipmentRegistry productionEquipmentRegistry,
            ProductionEquipmentOperationQueue operationQueue
    ) {
        this.buildingRegistry =
                Objects.requireNonNull(
                        buildingRegistry,
                        "buildingRegistry cannot be null"
                );

        this.buildingTypeRegistry =
                Objects.requireNonNull(
                        buildingTypeRegistry,
                        "buildingTypeRegistry cannot be null"
                );

        this.productionEquipmentRegistry =
                Objects.requireNonNull(
                        productionEquipmentRegistry,
                        "productionEquipmentRegistry cannot be null"
                );

        this.operationQueue =
                Objects.requireNonNull(
                        operationQueue,
                        "operationQueue cannot be null"
                );
    }

    /**
     * 向某栋建筑增加未安装设备。
     *
     * 适用于：
     * - 建造公司生产出设备；
     * - 设备被运输到某栋建筑；
     * - 未来其他“进入仓库”的场景。
     *
     * 此方法不会自动安装设备。
     */
    public ProductionEquipmentHolding addUninstalledEquipment(
            UUID buildingId,
            int amount
    ) {
        requirePositiveAmount(
                amount,
                "addUninstalledEquipment"
        );

        EconomicBuilding building =
                requireBuilding(buildingId);

        BuildingTypeDefinition buildingType =
                requireBuildingType(building);

        ProductionEquipmentHolding holding =
                getOrCreateHolding(
                        building,
                        buildingType
                );

        holding.addQuantity(amount);

        return holding;
    }

    /**
     * 增加设备，并尽可能自动安装。
     *
     * 第一版购买设备时可以使用这个入口。
     *
     * 超出安装上限的部分自动保持为 uninstalled。
     */
    public ProductionEquipmentHolding addEquipmentAndAutoInstall(
            UUID buildingId,
            int amount
    ) {
        requirePositiveAmount(
                amount,
                "addEquipmentAndAutoInstall"
        );

        EconomicBuilding building =
                requireBuilding(buildingId);

        BuildingTypeDefinition buildingType =
                requireBuildingType(building);

        ProductionEquipmentHolding holding =
                getOrCreateHolding(
                        building,
                        buildingType
                );

        /*
         * 设备先真正进入建筑。
         * 因此无论当前是否有 active batch，
         * totalQuantity 都立即增加。
         */
        holding.addQuantity(amount);

        int availableSlots =
                buildingType.maxProductionEquipment()
                        - holding.getInstalledQuantity();

        int installAmount =
                Math.min(
                        amount,
                        Math.max(
                                availableSlots,
                                0
                        )
                );

        /*
         * 不再直接 holding.install()。
         *
         * 统一通过 install()：
         *
         * 无 active batch -> EXECUTED
         * 有 active batch -> QUEUED
         */
        if (installAmount > 0) {
            install(
                    buildingId,
                    installAmount
            );
        }

        return holding;
    }

    public ProductionEquipmentOperationSubmission install(
            UUID buildingId,
            int amount
    ) {
        requirePositiveAmount(
                amount,
                "install"
        );

        EconomicBuilding building =
                requireBuilding(
                        buildingId
                );

        BuildingTypeDefinition buildingType =
                requireBuildingType(
                        building
                );

        /*
         * 提交阶段先确认 Holding 存在，
         * 并确认 Holding 与 BuildingType 一致。
         */
        requireHolding(
                building,
                buildingType
        );

        ProductionEquipmentOperation.Install operation =
                new ProductionEquipmentOperation.Install(
                        buildingId,
                        amount
                );

        /*
         * Active Batch：
         * 不修改 Holding，只进入 Queue。
         */
        if (building.getProductionDepartment()
                .hasActiveBatch()) {

            operationQueue.enqueue(
                    operation
            );

            return ProductionEquipmentOperationSubmission.queued(
                    operation
            );
        }

        /*
         * 没有 Active Batch：
         * 统一通过 executeOperation 真正执行。
         */
        executeOperation(
                operation
        );

        return ProductionEquipmentOperationSubmission.executed(
                operation
        );
    }

    private void executeInstall(
            UUID buildingId,
            BuildingTypeDefinition buildingType,
            ProductionEquipmentHolding holding,
            int amount
    ) {
        if ((long) holding.getInstalledQuantity() + amount
                > buildingType.maxProductionEquipment()) {

            throw new IllegalArgumentException(
                    "Cannot install "
                            + amount
                            + " production equipment for building "
                            + buildingId
                            + ": installation would exceed maxProductionEquipment="
                            + buildingType.maxProductionEquipment()
            );
        }

        holding.install(
                amount
        );
    }

    /**
     * 将已安装设备卸载回当前建筑仓库。
     *
     * totalQuantity 不变。
     */
    public ProductionEquipmentOperationSubmission uninstall(
            UUID buildingId,
            int amount
    ) {
        requirePositiveAmount(
                amount,
                "uninstall"
        );

        EconomicBuilding building =
                requireBuilding(
                        buildingId
                );

        BuildingTypeDefinition buildingType =
                requireBuildingType(
                        building
                );

        requireHolding(
                building,
                buildingType
        );

        ProductionEquipmentOperation.Uninstall operation =
                new ProductionEquipmentOperation.Uninstall(
                        buildingId,
                        amount
                );

        if (building.getProductionDepartment()
                .hasActiveBatch()) {

            operationQueue.enqueue(
                    operation
            );

            return ProductionEquipmentOperationSubmission.queued(
                    operation
            );
        }

        executeOperation(
                operation
        );

        return ProductionEquipmentOperationSubmission.executed(
                operation
        );
    }

    /**
     * 从建筑中移除未安装设备。
     *
     * 可以作为未来：
     * - 出售
     * - scrap
     * - 其他资产移出
     *
     * 的底层操作。
     *
     * 这里只允许移除 uninstalled equipment。
     */
    public ProductionEquipmentOperationSubmission removeUninstalledEquipment(
            UUID buildingId,
            int amount
    ) {
        requirePositiveAmount(
                amount,
                "removeUninstalledEquipment"
        );

        EconomicBuilding building =
                requireBuilding(
                        buildingId
                );

        BuildingTypeDefinition buildingType =
                requireBuildingType(
                        building
                );

        requireHolding(
                building,
                buildingType
        );

        ProductionEquipmentOperation.RemoveUninstalled operation =
                new ProductionEquipmentOperation.RemoveUninstalled(
                        buildingId,
                        amount
                );

        if (building.getProductionDepartment()
                .hasActiveBatch()) {

            operationQueue.enqueue(
                    operation
            );

            return ProductionEquipmentOperationSubmission.queued(
                    operation
            );
        }

        executeOperation(
                operation
        );

        return ProductionEquipmentOperationSubmission.executed(
                operation
        );
    }

    private void executeRemoveUninstalled(
            UUID buildingId,
            ProductionEquipmentHolding holding,
            int amount
    ) {
        holding.removeUninstalledQuantity(
                amount
        );

        if (holding.getTotalQuantity() == 0) {
            productionEquipmentRegistry.remove(
                    buildingId
            );
        }
    }


    /**
     * 在两栋建筑之间转移未安装设备。
     *
     * 第一版：
     * - 只允许转移 uninstalled equipment；
     * - 两栋建筑必须使用同一种 ProductionEquipmentType；
     * - 暂时不处理公司 ownership 和物流费用。
     */
    public ProductionEquipmentOperationSubmission transferUninstalledEquipment(
            UUID sourceBuildingId,
            UUID destinationBuildingId,
            int amount
    ) {
        requirePositiveAmount(
                amount,
                "transferUninstalledEquipment"
        );

        ProductionEquipmentOperation.Transfer operation =
                new ProductionEquipmentOperation.Transfer(
                        sourceBuildingId,
                        destinationBuildingId,
                        amount
                );

        EconomicBuilding sourceBuilding =
                requireBuilding(
                        sourceBuildingId
                );

        EconomicBuilding destinationBuilding =
                requireBuilding(
                        destinationBuildingId
                );

        BuildingTypeDefinition sourceType =
                requireBuildingType(
                        sourceBuilding
                );

        BuildingTypeDefinition destinationType =
                requireBuildingType(
                        destinationBuilding
                );

        /*
         * 设备类型兼容性属于比较稳定的提交阶段规则，
         * 可以现在就检查。
         */
        if (!sourceType.productionEquipmentTypeId()
                .equals(
                        destinationType.productionEquipmentTypeId()
                )) {

            throw new IllegalArgumentException(
                    "Cannot transfer production equipment from building "
                            + sourceBuildingId
                            + " to building "
                            + destinationBuildingId
                            + ": incompatible production equipment types"
            );
        }

        /*
         * Source 必须确实存在 Holding。
         *
         * 当前具体还有多少 uninstalled，
         * 不在这里检查，
         * 留到 executeTransfer() 真正执行时检查。
         */
        requireHolding(
                sourceBuilding,
                sourceType
        );

        /*
         * Transfer 同时涉及两栋建筑。
         *
         * 任意一栋有 Active Batch，
         * 都不能立即改变设备关系。
         */
        if (sourceBuilding.getProductionDepartment()
                .hasActiveBatch()
                || destinationBuilding.getProductionDepartment()
                .hasActiveBatch()) {

            operationQueue.enqueue(
                    operation
            );

            return ProductionEquipmentOperationSubmission.queued(
                    operation
            );
        }

        executeOperation(
                operation
        );

        return ProductionEquipmentOperationSubmission.executed(
                operation
        );
    }

    private void executeTransfer(
            EconomicBuilding sourceBuilding,
            EconomicBuilding destinationBuilding,
            BuildingTypeDefinition sourceType,
            BuildingTypeDefinition destinationType,
            int amount
    ) {
        ProductionEquipmentHolding sourceHolding =
                requireHolding(
                        sourceBuilding,
                        sourceType
                );

        if (amount
                > sourceHolding.getUninstalledQuantity()) {

            throw new IllegalArgumentException(
                    "Cannot transfer "
                            + amount
                            + " production equipment because source building only has "
                            + sourceHolding.getUninstalledQuantity()
                            + " uninstalled equipment"
            );
        }

        ProductionEquipmentHolding destinationHolding =
                getOrCreateHolding(
                        destinationBuilding,
                        destinationType
                );

        sourceHolding.removeUninstalledQuantity(
                amount
        );

        destinationHolding.addQuantity(
                amount
        );

        if (sourceHolding.getTotalQuantity() == 0) {
            productionEquipmentRegistry.remove(
                    sourceBuilding.getId()
            );
        }
    }

    /**
     * 获取建筑当前 Holding。
     *
     * 没有设备时返回 null。
     */
    public ProductionEquipmentHolding getHolding(
            UUID buildingId
    ) {
        return productionEquipmentRegistry.get(
                buildingId
        );
    }

    private EconomicBuilding requireBuilding(
            UUID buildingId
    ) {
        Objects.requireNonNull(
                buildingId,
                "buildingId cannot be null"
        );

        EconomicBuilding building =
                buildingRegistry.get(
                        buildingId
                );

        if (building == null) {
            throw new IllegalArgumentException(
                    "Unknown building: "
                            + buildingId
            );
        }

        return building;
    }

    private BuildingTypeDefinition requireBuildingType(
            EconomicBuilding building
    ) {
        BuildingTypeDefinition buildingType =
                buildingTypeRegistry.get(
                        building.getBuildingTypeId()
                );

        if (buildingType == null) {
            throw new IllegalStateException(
                    "Building "
                            + building.getId()
                            + " references unknown building type: "
                            + building.getBuildingTypeId()
            );
        }

        return buildingType;
    }

    private ProductionEquipmentHolding getOrCreateHolding(
            EconomicBuilding building,
            BuildingTypeDefinition buildingType
    ) {
        ProductionEquipmentHolding holding =
                productionEquipmentRegistry.get(
                        building.getId()
                );

        if (holding == null) {
            holding =
                    new ProductionEquipmentHolding(
                            building.getId(),
                            buildingType.productionEquipmentTypeId()
                    );

            productionEquipmentRegistry.register(
                    holding
            );

            return holding;
        }

        validateHolding(
                holding,
                buildingType
        );

        return holding;
    }

    private ProductionEquipmentHolding requireHolding(
            EconomicBuilding building,
            BuildingTypeDefinition buildingType
    ) {
        ProductionEquipmentHolding holding =
                productionEquipmentRegistry.get(
                        building.getId()
                );

        if (holding == null) {
            throw new IllegalStateException(
                    "Building "
                            + building.getId()
                            + " has no production equipment"
            );
        }

        validateHolding(
                holding,
                buildingType
        );

        return holding;
    }

    private void validateHolding(
            ProductionEquipmentHolding holding,
            BuildingTypeDefinition buildingType
    ) {
        if (!holding.getProductionEquipmentTypeId()
                .equals(buildingType.productionEquipmentTypeId())) {

            throw new IllegalStateException(
                    "Production equipment holding type '"
                            + holding.getProductionEquipmentTypeId()
                            + "' does not match building type equipment '"
                            + buildingType.productionEquipmentTypeId()
                            + "'"
            );
        }

        if (holding.getInstalledQuantity()
                > buildingType.maxProductionEquipment()) {

            throw new IllegalStateException(
                    "Production equipment holding has "
                            + holding.getInstalledQuantity()
                            + " installed equipment, exceeding building maximum "
                            + buildingType.maxProductionEquipment()
            );
        }
    }



    private static void requirePositiveAmount(
            int amount,
            String operation
    ) {
        if (amount <= 0) {
            throw new IllegalArgumentException(
                    operation
                            + " amount must be positive"
            );
        }
    }

    private void executeOperation(
            ProductionEquipmentOperation operation
    ) {
        switch (operation) {

            case ProductionEquipmentOperation.Install install -> {

                EconomicBuilding building =
                        requireBuilding(
                                install.buildingId()
                        );

                BuildingTypeDefinition buildingType =
                        requireBuildingType(
                                building
                        );

                ProductionEquipmentHolding holding =
                        requireHolding(
                                building,
                                buildingType
                        );

                executeInstall(
                        install.buildingId(),
                        buildingType,
                        holding,
                        install.amount()
                );
            }

            case ProductionEquipmentOperation.Uninstall uninstall -> {

                EconomicBuilding building =
                        requireBuilding(
                                uninstall.buildingId()
                        );

                BuildingTypeDefinition buildingType =
                        requireBuildingType(
                                building
                        );

                ProductionEquipmentHolding holding =
                        requireHolding(
                                building,
                                buildingType
                        );

                holding.uninstall(
                        uninstall.amount()
                );
            }

            case ProductionEquipmentOperation.RemoveUninstalled remove -> {

                EconomicBuilding building =
                        requireBuilding(
                                remove.buildingId()
                        );

                BuildingTypeDefinition buildingType =
                        requireBuildingType(
                                building
                        );

                ProductionEquipmentHolding holding =
                        requireHolding(
                                building,
                                buildingType
                        );

                executeRemoveUninstalled(
                        remove.buildingId(),
                        holding,
                        remove.amount()
                );
            }

            case ProductionEquipmentOperation.Transfer transfer -> {

                EconomicBuilding sourceBuilding =
                        requireBuilding(
                                transfer.sourceBuildingId()
                        );

                EconomicBuilding destinationBuilding =
                        requireBuilding(
                                transfer.destinationBuildingId()
                        );

                BuildingTypeDefinition sourceType =
                        requireBuildingType(
                                sourceBuilding
                        );

                BuildingTypeDefinition destinationType =
                        requireBuildingType(
                                destinationBuilding
                        );

                if (!sourceType.productionEquipmentTypeId()
                        .equals(
                                destinationType.productionEquipmentTypeId()
                        )) {

                    throw new IllegalStateException(
                            "Queued production equipment transfer "
                                    + "is no longer compatible"
                    );
                }

                executeTransfer(
                        sourceBuilding,
                        destinationBuilding,
                        sourceType,
                        destinationType,
                        transfer.amount()
                );
            }
        }
    }

    private boolean canExecuteNow(
            ProductionEquipmentOperation operation
    ) {
        return switch (operation) {

            case ProductionEquipmentOperation.Install install ->

                    !requireBuilding(
                            install.buildingId()
                    )
                            .getProductionDepartment()
                            .hasActiveBatch();

            case ProductionEquipmentOperation.Uninstall uninstall ->

                    !requireBuilding(
                            uninstall.buildingId()
                    )
                            .getProductionDepartment()
                            .hasActiveBatch();

            case ProductionEquipmentOperation.RemoveUninstalled remove ->

                    !requireBuilding(
                            remove.buildingId()
                    )
                            .getProductionDepartment()
                            .hasActiveBatch();

            case ProductionEquipmentOperation.Transfer transfer -> {

                EconomicBuilding sourceBuilding =
                        requireBuilding(
                                transfer.sourceBuildingId()
                        );

                EconomicBuilding destinationBuilding =
                        requireBuilding(
                                transfer.destinationBuildingId()
                        );

                yield !sourceBuilding
                        .getProductionDepartment()
                        .hasActiveBatch()
                        && !destinationBuilding
                        .getProductionDepartment()
                        .hasActiveBatch();
            }
        };
    }

    private Set<UUID> getInvolvedBuildingIds(
            ProductionEquipmentOperation operation
    ) {
        return switch (operation) {

            case ProductionEquipmentOperation.Install install ->
                    Set.of(
                            install.buildingId()
                    );

            case ProductionEquipmentOperation.Uninstall uninstall ->
                    Set.of(
                            uninstall.buildingId()
                    );

            case ProductionEquipmentOperation.RemoveUninstalled remove ->
                    Set.of(
                            remove.buildingId()
                    );

            case ProductionEquipmentOperation.Transfer transfer ->
                    Set.of(
                            transfer.sourceBuildingId(),
                            transfer.destinationBuildingId()
                    );
        };
    }

    public int flushPendingOperations() {

        int executedCount = 0;

        /*
         * 如果某栋建筑前面有一个暂时不能执行的操作，
         * 后面涉及同一建筑的操作不能越过它。
         */
        Set<UUID> blockedBuildings =
                new HashSet<>();

        /*
         * getAll() 返回的是 Queue 的快照，
         * 所以遍历过程中可以安全地从真正 Queue 中 remove。
         */
        for (ProductionEquipmentOperation operation
                : operationQueue.getAll()) {

            Set<UUID> involvedBuildingIds =
                    getInvolvedBuildingIds(
                            operation
                    );

            boolean blockedByEarlierOperation =
                    involvedBuildingIds.stream()
                            .anyMatch(
                                    blockedBuildings::contains
                            );

            if (blockedByEarlierOperation) {

                /*
                 * 当前操作也成为这些建筑前面的 pending operation。
                 */
                blockedBuildings.addAll(
                        involvedBuildingIds
                );

                continue;
            }

            /*
             * 例如对应 Building 仍然有 Active Batch。
             */
            if (!canExecuteNow(operation)) {

                blockedBuildings.addAll(
                        involvedBuildingIds
                );

                continue;
            }

            /*
             * 真正执行。
             *
             * 这里还会重新检查：
             * - installed 上限
             * - uninstalled 数量
             * - Holding
             * - equipment compatibility
             * 等运行时规则。
             */
            executeOperation(
                    operation
            );

            /*
             * 只有成功执行以后才从 Queue 删除。
             */
            operationQueue.remove(
                    operation
            );

            executedCount++;
        }

        return executedCount;
    }

    public boolean hasPendingOperationsForBuilding(
            UUID buildingId
    ) {
        Objects.requireNonNull(
                buildingId,
                "buildingId cannot be null"
        );

        return operationQueue.hasPendingForBuilding(
                buildingId
        );
    }

    public int flushPendingOperationsForBuilding(
            UUID boundaryBuildingId
    ) {
        Objects.requireNonNull(
                boundaryBuildingId,
                "boundaryBuildingId cannot be null"
        );

        int executedCount = 0;

        /*
         * 某栋建筑如果前面已经存在一个还不能执行的 Operation，
         * 后面涉及这栋建筑的 Operation 不能越过去。
         */
        Set<UUID> blockedBuildings =
                new HashSet<>();

        /*
         * getAll() 是快照，所以遍历过程中可以安全地
         * 从真正的 Queue 中 remove。
         */
        for (ProductionEquipmentOperation operation
                : operationQueue.getAll()) {

            Set<UUID> involvedBuildingIds =
                    getInvolvedBuildingIds(
                            operation
                    );

            boolean involvesBoundaryBuilding =
                    involvedBuildingIds.contains(
                            boundaryBuildingId
                    );

            /*
             * 当前是 boundaryBuilding 到达了 Batch Boundary。
             *
             * 与它完全无关的 Operation 本轮不主动执行。
             *
             * 但是它仍然是一条更早的 pending operation，
             * 所以应该阻塞它所涉及建筑的后续操作。
             *
             * 例如：
             *
             * 1. Uninstall(B)
             * 2. Transfer(A -> B)
             *
             * A 到边界时不能越过 #1 直接执行 #2。
             */
            if (!involvesBoundaryBuilding) {
                blockedBuildings.addAll(
                        involvedBuildingIds
                );

                continue;
            }

            boolean blockedByEarlierOperation =
                    involvedBuildingIds.stream()
                            .anyMatch(
                                    blockedBuildings::contains
                            );

            if (blockedByEarlierOperation) {
                blockedBuildings.addAll(
                        involvedBuildingIds
                );

                continue;
            }

            /*
             * 当前 Operation 自身的执行条件仍未满足。
             *
             * 例如 Transfer(A -> B)，
             * 虽然 A 到边界了，
             * 但是 B 仍有 Active Batch。
             */
            if (!canExecuteNow(operation)) {
                blockedBuildings.addAll(
                        involvedBuildingIds
                );

                continue;
            }

            /*
             * 真正执行领域操作。
             */
            executeOperation(
                    operation
            );

            /*
             * 只有成功以后才删除。
             */
            operationQueue.remove(
                    operation
            );

            executedCount++;
        }

        return executedCount;
    }
}