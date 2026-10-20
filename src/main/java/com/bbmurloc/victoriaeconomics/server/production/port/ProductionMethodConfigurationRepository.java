package com.bbmurloc.victoriaeconomics.server.production.port;

import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionMethodConfiguration;
import java.util.UUID;

public interface ProductionMethodConfigurationRepository {
    /** Standalone committed read; never joins an unresolved local transaction. */
    ProductionMethodConfiguration load(UUID buildingId);
    void create(ProductionMethodConfiguration initial);
    void save(ProductionMethodConfiguration changed, long expectedRevision);

    /** Working revisions become durable only when the owning UoW returns normally. */
    interface Session {
        ProductionMethodConfiguration load(UUID buildingId);
        void save(ProductionMethodConfiguration changed, long expectedRevision);
    }
}
