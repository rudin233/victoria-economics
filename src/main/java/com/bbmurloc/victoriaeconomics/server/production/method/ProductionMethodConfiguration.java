package com.bbmurloc.victoriaeconomics.server.production.method;

import com.bbmurloc.victoriaeconomics.server.production.calculation.ResolvedProductionRecipe;

import java.util.*;

/**
 * Owns a valid effective configuration and at most one valid pending target.
 */
public final class ProductionMethodConfiguration {
    private final ProductionMethodRules rules;
    private ProductionMethodSelections effective;
    private ProductionMethodSelections pending;

    public ProductionMethodConfiguration(ProductionMethodRules rules, Map<String, String> effective, Map<String, String> pending) {
        this.rules = Objects.requireNonNull(rules);
        rules.resolve(effective);
        this.effective = new ProductionMethodSelections(effective);
        if (pending != null) {
            rules.resolve(pending);
            this.pending = new ProductionMethodSelections(pending);
        }
    }

    public ProductionMethodSelections effective() {
        return effective;
    }

    public Optional<ProductionMethodSelections> pending() {
        return Optional.ofNullable(pending);
    }

    public ResolvedProductionRecipe recipe() {
        return rules.resolve(effective.methods());
    }

    public void requestSelection(String group, String method, boolean defer) {
        Map<String, String> target = new HashMap<>(pending == null ? effective.methods() : pending.methods());
        target.put(group, method);
        requestTarget(target, defer);
    }

    public void requestTarget(Map<String, String> target, boolean defer) {
        rules.resolve(target);
        ProductionMethodSelections validated = new ProductionMethodSelections(target);
        if (defer) pending = validated.equals(effective) ? null : validated;
        else {
            effective = validated;
            pending = null;
        }
    }

    public void cancelPending() {
        pending = null;
    }

    public static final class State {
        private final ProductionMethodConfiguration owner;
        private final Map<String, String> effective, pending;

        private State(ProductionMethodConfiguration owner) {
            this.owner = owner;
            effective = owner.effective.methods();
            pending = owner.pending == null ? null : owner.pending.methods();
        }

        public Map<String, String> effective() {
            return effective;
        }

        public Map<String, String> pending() {
            return pending;
        }
    }

    public State state() {
        return new State(this);
    }

    public void restore(State state) {
        if (state.owner != this) throw new IllegalArgumentException("Foreign PM rollback checkpoint");
        rules.resolve(state.effective());
        if (state.pending() != null) rules.resolve(state.pending());
        effective = new ProductionMethodSelections(state.effective());
        pending = state.pending() == null ? null : new ProductionMethodSelections(state.pending());
    }

    public boolean applyPending() {
        if (pending == null) return false;
        effective = pending;
        pending = null;
        return true;
    }
}
