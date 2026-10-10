package com.bbmurloc.victoriaeconomics.server.production.port;

public final class ProductionRevisionConflict extends IllegalStateException {
    public ProductionRevisionConflict(String message) { super(message); }
}
