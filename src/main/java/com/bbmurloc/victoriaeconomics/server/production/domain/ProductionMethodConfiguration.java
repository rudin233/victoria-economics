package com.bbmurloc.victoriaeconomics.server.production.domain;

import java.util.*;

/**
 * Owns a valid effective configuration and at most one valid pending target.
 */
public final class ProductionMethodConfiguration {
    private final UUID buildingId;
    private final ProductionMethodRules rules;
    private long configurationRevision;
    private long effectiveRevision;
    private ProductionMethodSelections effective;
    private ProductionMethodSelections pending;

    public ProductionMethodConfiguration(UUID buildingId, ProductionMethodRules rules, Map<String, String> effective, Map<String, String> pending) {
        this(buildingId, rules, effective, pending, 0, 0);
    }

    public ProductionMethodConfiguration(UUID buildingId, ProductionMethodRules rules, Map<String, String> effective,
                                         Map<String, String> pending, long configurationRevision, long effectiveRevision) {
        this.buildingId = Objects.requireNonNull(buildingId);
        if (configurationRevision < 0 || effectiveRevision < 0 || effectiveRevision > configurationRevision)
            throw new IllegalArgumentException("Invalid production configuration revisions");
        this.configurationRevision = configurationRevision;
        this.effectiveRevision = effectiveRevision;
        this.rules = Objects.requireNonNull(rules);
        rules.resolve(effective);
        this.effective = new ProductionMethodSelections(effective);
        if (pending != null) {
            rules.resolve(pending);
            this.pending = new ProductionMethodSelections(pending);
        }
    }

    public UUID buildingId() { return buildingId; }
    public long configurationRevision() { return configurationRevision; }
    public long effectiveRevision() { return effectiveRevision; }

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
        var oldEffective = effective;
        var oldPending = pending;
        if (defer) pending = validated.equals(effective) ? null : validated;
        else {
            effective = validated;
            pending = null;
        }
        recordChange(oldEffective, oldPending);
    }

    private void recordChange(ProductionMethodSelections oldEffective, ProductionMethodSelections oldPending) {
        if (!effective.equals(oldEffective) || !Objects.equals(pending, oldPending)) {
            configurationRevision = Math.incrementExact(configurationRevision);
            if (!effective.equals(oldEffective)) effectiveRevision = Math.incrementExact(effectiveRevision);
        }
    }

    public void cancelPending() {
        var oldPending = pending;
        pending = null;
        recordChange(effective, oldPending);
    }

    public static final class State {
        private final ProductionMethodConfiguration owner;
        private final Map<String, String> effective, pending;
        private final long configurationRevision, effectiveRevision;

        private State(ProductionMethodConfiguration owner) {
            this.owner = owner;
            effective = owner.effective.methods();
            pending = owner.pending == null ? null : owner.pending.methods();
            configurationRevision = owner.configurationRevision;
            effectiveRevision = owner.effectiveRevision;
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
        configurationRevision = state.configurationRevision;
        effectiveRevision = state.effectiveRevision;
    }

    public boolean applyPending() {
        if (pending == null) return false;
        var oldEffective = effective;
        var oldPending = pending;
        effective = pending;
        pending = null;
        recordChange(oldEffective, oldPending);
        return true;
    }
}
