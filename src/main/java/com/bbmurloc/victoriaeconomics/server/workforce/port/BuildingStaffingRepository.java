package com.bbmurloc.victoriaeconomics.server.workforce.port;

import com.bbmurloc.victoriaeconomics.server.workforce.domain.BuildingStaffing;
import java.util.UUID;

public interface BuildingStaffingRepository {
    BuildingStaffing load(UUID building);
    /** Storage capability for a future payroll-aware outer transaction; not an effective hiring command. */
    interface Session {
        BuildingStaffing load(UUID building);
        void createEmpty(UUID building);
        void save(BuildingStaffing changed, long expectedRevision);
    }
}
