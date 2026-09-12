package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.building.BuildingRepository;
import com.bbmurloc.victoriaeconomics.server.building.BuildingStatus;
import com.bbmurloc.victoriaeconomics.server.building.EconomicBuilding;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SqliteBuildingRepository
        implements BuildingRepository {

    private final Connection connection;

    public SqliteBuildingRepository(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void save(EconomicBuilding building) {
        boolean oldAutoCommit;

        try {
            oldAutoCommit =
                    connection.getAutoCommit();

            connection.setAutoCommit(false);

            try {
                /*
                 * EconomicBuilding 主体和 PM selections
                 * 必须作为同一次事务保存。
                 */
                saveBuildingRow(building);
                saveProductionMethodSelections(building);

                connection.commit();

            } catch (SQLException e) {

                connection.rollback();

                throw e;

            } finally {

                connection.setAutoCommit(
                        oldAutoCommit
                );
            }

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to save economic building: "
                            + building.getId(),
                    e
            );
        }
    }

    /**
     * 保存 EconomicBuilding 主体。
     */
    private void saveBuildingRow(
            EconomicBuilding building
    ) throws SQLException {

        String sql = """
                INSERT INTO economic_buildings (
                    id,
                    building_type_id,
                    status,
                    current_equipment
                )
                VALUES (?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    building_type_id = excluded.building_type_id,
                    status = excluded.status,
                    current_equipment = excluded.current_equipment
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(
                    1,
                    building.getId().toString()
            );

            statement.setString(
                    2,
                    building.getBuildingTypeId()
            );

            statement.setString(
                    3,
                    building.getStatus().name()
            );

            statement.setInt(
                    4,
                    building.getCurrentEquipment()
            );

            statement.executeUpdate();
        }
    }

    /**
     * 保存：
     *
     * PMG ID -> 当前选中的 PM ID
     */
    private void saveProductionMethodSelections(
            EconomicBuilding building
    ) throws SQLException {

        String deleteSql = """
                DELETE FROM building_production_method_selections
                WHERE building_id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(deleteSql)) {

            statement.setString(
                    1,
                    building.getId().toString()
            );

            statement.executeUpdate();
        }

        Map<String, String> selections =
                building.getSelectedProductionMethods();

        if (selections.isEmpty()) {
            return;
        }

        String insertSql = """
                INSERT INTO building_production_method_selections (
                    building_id,
                    group_id,
                    method_id
                )
                VALUES (?, ?, ?)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(insertSql)) {

            for (Map.Entry<String, String> entry
                    : selections.entrySet()) {

                statement.setString(
                        1,
                        building.getId().toString()
                );

                statement.setString(
                        2,
                        entry.getKey()
                );

                statement.setString(
                        3,
                        entry.getValue()
                );

                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    @Override
    public void delete(UUID buildingId) {
        boolean oldAutoCommit;

        try {
            oldAutoCommit =
                    connection.getAutoCommit();

            connection.setAutoCommit(false);

            try {
                deleteProductionMethodSelections(
                        buildingId
                );

                deleteBuildingRow(
                        buildingId
                );

                connection.commit();

            } catch (SQLException e) {

                connection.rollback();

                throw e;

            } finally {

                connection.setAutoCommit(
                        oldAutoCommit
                );
            }

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to delete economic building: "
                            + buildingId,
                    e
            );
        }
    }

    private void deleteProductionMethodSelections(
            UUID buildingId
    ) throws SQLException {

        String sql = """
                DELETE FROM building_production_method_selections
                WHERE building_id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(
                    1,
                    buildingId.toString()
            );

            statement.executeUpdate();
        }
    }

    private void deleteBuildingRow(
            UUID buildingId
    ) throws SQLException {

        String sql = """
                DELETE FROM economic_buildings
                WHERE id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(
                    1,
                    buildingId.toString()
            );

            statement.executeUpdate();
        }
    }

    @Override
    public List<EconomicBuilding> loadAll() {

        Map<UUID, EconomicBuilding> buildings =
                new LinkedHashMap<>();

        /*
         * 第一阶段：
         * 恢复建筑主体。
         */
        loadBuildingRows(buildings);

        /*
         * 第二阶段：
         * 恢复 PM selections。
         */
        loadProductionMethodSelections(
                buildings
        );

        return new ArrayList<>(
                buildings.values()
        );
    }

    /**
     * 恢复：
     *
     * id
     * buildingTypeId
     * status
     * currentEquipment
     */
    private void loadBuildingRows(
            Map<UUID, EconomicBuilding> buildings
    ) {

        String sql = """
                SELECT
                    id,
                    building_type_id,
                    status,
                    current_equipment
                FROM economic_buildings
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql);

             ResultSet resultSet =
                     statement.executeQuery()) {

            while (resultSet.next()) {

                UUID id =
                        UUID.fromString(
                                resultSet.getString(
                                        "id"
                                )
                        );

                String buildingTypeId =
                        resultSet.getString(
                                "building_type_id"
                        );

                BuildingStatus status =
                        BuildingStatus.valueOf(
                                resultSet.getString(
                                        "status"
                                )
                        );

                int currentEquipment =
                        resultSet.getInt(
                                "current_equipment"
                        );

                /*
                 * 沿用你现在已有的两参数构造器。
                 */
                EconomicBuilding building =
                        new EconomicBuilding(
                                id,
                                buildingTypeId
                        );

                building.setStatus(
                        status
                );

                building.setCurrentEquipment(
                        currentEquipment
                );

                buildings.put(
                        id,
                        building
                );
            }

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to load economic buildings",
                    e
            );
        }
    }

    /**
     * 恢复：
     *
     * PMG ID -> selected PM ID
     */
    private void loadProductionMethodSelections(
            Map<UUID, EconomicBuilding> buildings
    ) {

        String sql = """
                SELECT
                    building_id,
                    group_id,
                    method_id
                FROM building_production_method_selections
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql);

             ResultSet resultSet =
                     statement.executeQuery()) {

            while (resultSet.next()) {

                UUID buildingId =
                        UUID.fromString(
                                resultSet.getString(
                                        "building_id"
                                )
                        );

                String groupId =
                        resultSet.getString(
                                "group_id"
                        );

                String methodId =
                        resultSet.getString(
                                "method_id"
                        );

                EconomicBuilding building =
                        buildings.get(
                                buildingId
                        );

                if (building != null) {

                    building.setSelectedProductionMethod(
                            groupId,
                            methodId
                    );
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to load building production method selections",
                    e
            );
        }
    }
}