package com.bbmurloc.victoriaeconomics.server.workforce.port;

import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import com.bbmurloc.victoriaeconomics.server.workforce.domain.RegionalLaborSupply;
import java.util.Optional;

public interface RegionalLaborSupplyRepository {
    Optional<RegionalLaborSupply> find(RegionId region, String occupation);
    long occupied(RegionId region, String occupation);
    interface Session {
        Optional<RegionalLaborSupply> find(RegionId region, String occupation);
        long occupied(RegionId region, String occupation);
        void create(RegionalLaborSupply initial);
        void save(RegionalLaborSupply changed, long expectedRevision);
    }
}
