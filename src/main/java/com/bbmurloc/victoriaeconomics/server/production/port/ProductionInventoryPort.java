package com.bbmurloc.victoriaeconomics.server.production.port;

import java.util.*;

/**
 * Production's requests to the inventory authority. Query results and acknowledgements
 * expose no inventory aggregate state and are never persisted as inventory balances.
 */
public interface ProductionInventoryPort {
    record StorageStatus(boolean overCapacity) {}

    /** Confirms that inventory has durably settled this batch, including on a matching retry. */
    record SettlementReceipt(UUID batchId) {
        public SettlementReceipt {
            Objects.requireNonNull(batchId);
        }
    }

    /** Current capacity fact, not a permit to start; reservation checks the authority again. */
    StorageStatus storageStatus(UUID location);

    /** Reserves the whole recipe exclusively at this location; uncertain failures remain compensatable. */
    void reserve(UUID location, UUID batch, Map<String, Double> inputs);

    boolean isReserved(UUID location, UUID batch, Map<String, Double> inputs);

    void release(UUID location, UUID batch);

    /**
     * Atomically consumes/produces at the given progress and releases remaining inputs.
     * Committed output may cause overflow. Matching retries acknowledge the same settlement;
     * conflicting retries fail. A receipt is returned only after inventory persistence succeeds.
     */
    SettlementReceipt settle(UUID location, UUID batch, Map<String, Double> inputs, Map<String, Double> outputs, double progress);
}
