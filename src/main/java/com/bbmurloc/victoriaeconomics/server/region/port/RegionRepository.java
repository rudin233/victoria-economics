package com.bbmurloc.victoriaeconomics.server.region.port;

import com.bbmurloc.victoriaeconomics.server.region.domain.*;
import java.util.Optional;

public interface RegionRepository {
    void create(Region region);
    Optional<Region> find(RegionId id);
}
