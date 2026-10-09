package com.bbmurloc.victoriaeconomics.server.production.port;

import java.util.*;

/**
 * Durable start intents survive a crash between material reservation and batch creation.
 */
public interface ProductionJournal {
    record StartIntent(UUID batchId, UUID buildingId) {
    }

    void recordStart(StartIntent intent);

    void finishStart(UUID batchId);

    List<StartIntent> pendingStarts();

    boolean containsBatch(UUID batchId);
}
