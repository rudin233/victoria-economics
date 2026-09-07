package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.building.BuildingRepository;
import com.bbmurloc.victoriaeconomics.server.building.BuildingStatus;
import com.bbmurloc.victoriaeconomics.server.building.EconomicBuilding;
import com.bbmurloc.victoriaeconomics.server.storage.EconomyDatabase;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SqliteBuildingRepository
        implements BuildingRepository {

    private final EconomyDatabase database;

    public SqliteBuildingRepository(EconomyDatabase database) {
        this.database = database;
    }

    @Override
    public void save(EconomicBuilding building) {

        String sql = """
                INSERT INTO economic_buildings (
                    id,
                    building_type_id,
                    status
                )
                VALUES (?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    building_type_id = excluded.building_type_id,
                    status = excluded.status
                """;

        try (PreparedStatement statement =
                     database.getConnection().prepareStatement(sql)) {

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

            statement.executeUpdate();

        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed to save building " + building.getId(),
                    exception
            );
        }
    }

    @Override
    public void delete(UUID buildingId) {

        String sql = """
                DELETE FROM economic_buildings
                WHERE id = ?
                """;

        try (PreparedStatement statement =
                     database.getConnection().prepareStatement(sql)) {

            statement.setString(
                    1,
                    buildingId.toString()
            );

            statement.executeUpdate();

        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed to delete building " + buildingId,
                    exception
            );
        }
    }

    @Override
    public List<EconomicBuilding> loadAll() {

        String sql = """
                SELECT
                    id,
                    building_type_id,
                    status
                FROM economic_buildings
                """;

        List<EconomicBuilding> buildings =
                new ArrayList<>();

        try (PreparedStatement statement =
                     database.getConnection().prepareStatement(sql);

             ResultSet resultSet =
                     statement.executeQuery()) {

            while (resultSet.next()) {

                UUID id =
                        UUID.fromString(
                                resultSet.getString("id")
                        );

                String buildingTypeId =
                        resultSet.getString(
                                "building_type_id"
                        );

                BuildingStatus status =
                        BuildingStatus.valueOf(
                                resultSet.getString("status")
                        );

                EconomicBuilding building =
                        new EconomicBuilding(
                                id,
                                buildingTypeId
                        );

                building.setStatus(status);

                buildings.add(building);
            }

            return buildings;

        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed to load economic buildings",
                    exception
            );
        }
    }
}