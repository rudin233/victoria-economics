package com.bbmurloc.victoriaeconomics.server.storage;

import com.bbmurloc.victoriaeconomics.server.production.port.ProductionExecutionRepository;
import com.bbmurloc.victoriaeconomics.server.production.port.ProductionMethodConfigurationRepository;

/** One atomic business transaction, acquiring write permission before authoritative application reads.
 * The formal server uses the shared RAM store; disk durability is a separate whole-economy checkpoint. */
public interface EconomyUnitOfWork {
    /**
     * Session roots are detached working objects, never cache authority during the callback.
     * Save each loaded root at most once. Saves register the root's final callback state and are
     * flushed after the callback; only normal return
     * confirms transaction visibility, not restart durability. On any failure (including unknown commit), discard all session roots and
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
