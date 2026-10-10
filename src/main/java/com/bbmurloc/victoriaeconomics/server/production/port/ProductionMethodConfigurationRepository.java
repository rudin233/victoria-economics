package com.bbmurloc.victoriaeconomics.server.production.port;

import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionMethodConfiguration;
import java.util.UUID;

public interface ProductionMethodConfigurationRepository {
    ProductionMethodConfiguration load(UUID buildingId);
    void create(ProductionMethodConfiguration initial);
    void save(ProductionMethodConfiguration changed, long expectedRevision);
}
