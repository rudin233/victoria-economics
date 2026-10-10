package com.bbmurloc.victoriaeconomics.server.production.port;

import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionExecution;
import java.util.UUID;

public interface ProductionExecutionRepository {
    ProductionExecution load(UUID buildingId);
    void create(ProductionExecution initial);
    void save(ProductionExecution changed, long expectedRevision);
}
