package com.bbmurloc.victoriaeconomics.server.storage;

import com.bbmurloc.victoriaeconomics.server.production.port.ProductionExecutionRepository;
import com.bbmurloc.victoriaeconomics.server.production.port.ProductionMethodConfigurationRepository;

/** A single physical write transaction, acquiring write permission before invoking application work. */
public interface EconomyUnitOfWork {
    /**
     * Session roots are detached working objects, never cache authority during the callback.
     * Save each loaded root at most once. Saves register the root's final callback state and are
     * flushed after the callback; only normal return
     * confirms durability. On any failure (including unknown commit), discard all session roots and
     * reload committed facts. Neither the callback nor commit is automatically retried.
     */
    void write(Work work);

    @FunctionalInterface
    interface Work { void run(Session session); }

    interface Session {
        ProductionMethodConfigurationRepository.Session methods();
        ProductionExecutionRepository.Session executions();
    }
}
