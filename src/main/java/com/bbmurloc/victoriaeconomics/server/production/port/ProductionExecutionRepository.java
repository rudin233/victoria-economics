package com.bbmurloc.victoriaeconomics.server.production.port;

import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionExecution;
import java.util.UUID;

public interface ProductionExecutionRepository {
    /** Standalone committed read; never joins an unresolved local transaction. */
    ProductionExecution load(UUID buildingId);
    void create(ProductionExecution initial);
    void save(ProductionExecution changed, long expectedRevision);

    /** Detached roots owned by one explicit UoW; save does not acknowledge a physical commit. */
    interface Session {
        ProductionExecution load(UUID buildingId);
        void save(ProductionExecution changed, long expectedRevision);
    }
}
