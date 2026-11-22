package com.bbmurloc.victoriaeconomics.server.workforce.port;

import com.bbmurloc.victoriaeconomics.server.workforce.domain.BuildingStaffing;
import java.util.UUID;
import java.util.Map;

public interface BuildingStaffingRepository {
    BuildingStaffing load(UUID building);
    /** 批次开工与终结事务使用的占用写入能力。 */
    interface Session {
        BuildingStaffing load(UUID building);
        void createEmpty(UUID building);
        void acquire(UUID building, UUID batch, Map<String, Integer> workers, long expectedRevision);
        void release(UUID building, UUID batch, long expectedRevision);
    }
}
