package com.bbmurloc.victoriaeconomics.server.production.application;

/** Known business admission refusal; unlike persistence failure it does not leave a Tick incomplete. */
public final class ProductionStartBlocked extends IllegalStateException {
    public ProductionStartBlocked(String message){super(message);}
}
