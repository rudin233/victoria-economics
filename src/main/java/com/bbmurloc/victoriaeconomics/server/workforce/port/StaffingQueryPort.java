package com.bbmurloc.victoriaeconomics.server.workforce.port;

import com.bbmurloc.victoriaeconomics.server.workforce.domain.BuildingStaffing;
import java.util.*;

public interface StaffingQueryPort {
    record Supply(OptionalLong total, long occupied) {}
    record Facts(BuildingStaffing staffing, Map<String, Integer> capacity, long effectivePmRevision, Map<String, Supply> supply) {
        public Facts { capacity = Map.copyOf(capacity); supply = Map.copyOf(supply); }
    }
    Facts read(UUID building);
}
