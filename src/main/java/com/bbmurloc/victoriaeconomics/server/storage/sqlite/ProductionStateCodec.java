package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.production.*;
import com.bbmurloc.victoriaeconomics.server.production.batch.*;
import com.google.gson.Gson;

import java.util.UUID;

/**
 * Explicit immutable DTOs reconstruct through domain validation instead of bypassing constructors.
 */
final class ProductionStateCodec {
    record Batch(UUID id, UUID buildingId, ProductionBatchConfiguration configuration, long progressUnits,
                 ProductionBatch.Status status) {
        static Batch from(ProductionBatch batch) {
            return batch == null ? null : new Batch(batch.getId(), batch.getBuildingId(), batch.getConfiguration(), batch.getProgressUnits(), batch.getStatus());
        }

        ProductionBatch domain() {
            return new ProductionBatch(id, buildingId, configuration, progressUnits, status);
        }
    }

    record Execution(Batch batch, Batch lastBatch, ProductionExecution.Boundary boundary, boolean automatic) {
    }

    private final Gson gson = new Gson();

    String encode(ProductionExecution execution) {
        return gson.toJson(new Execution(Batch.from(execution.batch()), Batch.from(execution.lastBatch()), execution.boundary(), execution.automatic()));
    }

    String encodeBatch(ProductionBatch batch) {
        return gson.toJson(Batch.from(batch));
    }

    void restore(String json, ProductionExecution target) {
        Execution state = gson.fromJson(json, Execution.class);
        target.restore(state.batch() == null ? null : state.batch().domain(), state.lastBatch() == null ? null : state.lastBatch().domain(), state.boundary(), state.automatic());
    }
}
