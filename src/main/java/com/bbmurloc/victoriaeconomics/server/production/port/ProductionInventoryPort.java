package com.bbmurloc.victoriaeconomics.server.production.port;

import java.util.*;
import com.bbmurloc.victoriaeconomics.server.inventory.domain.GoodsInventory;

public interface ProductionInventoryPort {
    GoodsInventory.State inspect(UUID location);

    void reserve(UUID location, UUID batch, Map<String, Double> inputs);

    boolean isReserved(UUID location, UUID batch, Map<String, Double> inputs);

    void release(UUID location, UUID batch);

    GoodsInventory.Settlement settle(UUID location, UUID batch, Map<String, Double> inputs, Map<String, Double> outputs, double progress);
}
