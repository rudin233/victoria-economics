package com.bbmurloc.victoriaeconomics.server.production.method;

import java.util.Map;

/**
 * A complete immutable target; its configuration owner validates it.
 */
public record ProductionMethodSelections(Map<String, String> methods) {
    public ProductionMethodSelections {
        methods = Map.copyOf(methods);
    }
}
