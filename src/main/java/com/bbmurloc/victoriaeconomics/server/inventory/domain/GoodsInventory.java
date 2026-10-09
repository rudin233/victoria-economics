package com.bbmurloc.victoriaeconomics.server.inventory.domain;

import java.util.*;

/**
 * A single physical storage location. Equipment is a separate aggregate and capacity system.
 */
public final class GoodsInventory {
    public record Settlement(UUID batchId, Map<String, Double> consumed, Map<String, Double> produced) {
        public Settlement {
            consumed = Map.copyOf(consumed);
            produced = Map.copyOf(produced);
        }
    }

    public record State(UUID location, double capacity, Map<String, Double> quantities,
                        Map<UUID, Map<String, Double>> reservations, Map<UUID, Settlement> settlements) {
        public State {
            Objects.requireNonNull(location);
            if (!Double.isFinite(capacity) || capacity < 0)
                throw new IllegalArgumentException("Invalid storage capacity");
            quantities = copyQuantities(quantities);
            Map<UUID, Map<String, Double>> locks = new HashMap<>();
            reservations.forEach((id, amounts) -> locks.put(id, copyQuantities(amounts)));
            reservations = Map.copyOf(locks);
            settlements = Map.copyOf(settlements);
        }
    }

    private final UUID location;
    private final double capacity;
    private final Map<String, Double> quantities;
    private final Map<UUID, Map<String, Double>> reservations;
    private final Map<UUID, Settlement> settlements;

    public GoodsInventory(UUID location, double capacity) {
        this(new State(location, capacity, Map.of(), Map.of(), Map.of()));
    }

    public GoodsInventory(State state) {
        location = state.location();
        capacity = state.capacity();
        quantities = new HashMap<>(state.quantities());
        reservations = new HashMap<>(state.reservations());
        settlements = new HashMap<>(state.settlements());
        for (String good : quantities.keySet()) {
            if (reserved(good) > quantity(good))
                throw new IllegalArgumentException("Reservation exceeds stock: " + good);
        }
        for (var amounts : reservations.values()) {
            for (String good : amounts.keySet())
                if (reserved(good) > quantity(good))
                    throw new IllegalArgumentException("Reservation exceeds stock: " + good);
        }
        for (UUID batch : reservations.keySet()) {
            if (settlements.containsKey(batch))
                throw new IllegalArgumentException("Settled batch still holds materials");
        }
    }

    public State state() {
        return new State(location, capacity, quantities, reservations, settlements);
    }

    public double quantity(String good) {
        return quantities.getOrDefault(good, 0.0);
    }

    public double reserved(String good) {
        return reservations.values().stream().mapToDouble(r -> r.getOrDefault(good, 0.0)).sum();
    }

    public double available(String good) {
        return quantity(good) - reserved(good);
    }

    public double used() {
        return quantities.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    public boolean overCapacity() {
        return used() > capacity;
    }

    public void deposit(Map<String, Double> amounts) {
        var checked = copyQuantities(amounts);
        if (overCapacity() || used() + checked.values().stream().mapToDouble(Double::doubleValue).sum() > capacity) {
            throw new IllegalStateException("Ordinary storage intake exceeds capacity: " + location);
        }
        Map<String, Double> next = new HashMap<>(quantities);
        checked.forEach((good, q) -> next.merge(good, q, Double::sum));
        copyQuantities(next);
        quantities.clear();
        quantities.putAll(next);
    }

    public void withdraw(Map<String, Double> amounts) {
        var checked = copyQuantities(amounts);
        checked.forEach((good, q) -> {
            if (q > available(good)) throw new IllegalStateException("Insufficient available stock: " + good);
        });
        checked.forEach((good, q) -> quantities.put(good, quantity(good) - q));
    }

    /**
     * All inputs commit together or none do. A repeated reservation must match the same commitment.
     */
    public void reserve(UUID batch, Map<String, Double> inputs) {
        Objects.requireNonNull(batch);
        var checked = copyQuantities(inputs);
        if (settlements.containsKey(batch)) throw new IllegalStateException("Batch has already settled");
        if (reservations.containsKey(batch)) {
            if (!reservations.get(batch).equals(checked)) throw new IllegalStateException("Reservation id reused");
            return;
        }
        if (overCapacity()) throw new IllegalStateException("Overfull storage cannot start production");
        checked.forEach((good, q) -> {
            if (q > available(good)) throw new IllegalStateException("Missing batch input: " + good);
        });
        reservations.put(batch, checked);
    }

    public boolean hasReservation(UUID batch, Map<String, Double> inputs) {
        return inputs.equals(reservations.get(batch));
    }

    public void release(UUID batch) {
        reservations.remove(batch);
    }

    public Settlement settlement(UUID batch) {
        return settlements.get(batch);
    }

    /**
     * Consumption, committed output intake, remaining release and receipt form one atomic domain change.
     */
    public Settlement settle(UUID batch, Map<String, Double> inputs, Map<String, Double> outputs, double progress) {
        if (!Double.isFinite(progress) || progress < 0 || progress > 1)
            throw new IllegalArgumentException("Invalid settlement progress");
        var consumed = proportional(inputs, progress);
        var produced = proportional(outputs, progress);
        Settlement desired = new Settlement(batch, consumed, produced);
        Settlement prior = settlements.get(batch);
        if (prior != null) {
            if (!prior.equals(desired)) throw new IllegalStateException("Conflicting repeated settlement");
            return prior;
        }
        if (!hasReservation(batch, inputs))
            throw new IllegalStateException("Batch material commitment not held: " + batch);
        Map<String, Double> next = new HashMap<>(quantities);
        consumed.forEach((good, q) -> next.put(good, next.getOrDefault(good, 0.0) - q));
        produced.forEach((good, q) -> next.merge(good, q, Double::sum));
        copyQuantities(next); // Validate before mutating. A committed batch may exceed ordinary capacity.
        quantities.clear();
        quantities.putAll(next);
        reservations.remove(batch);
        settlements.put(batch, desired);
        return desired;
    }

    private static Map<String, Double> proportional(Map<String, Double> amounts, double fraction) {
        Map<String, Double> result = new HashMap<>();
        copyQuantities(amounts).forEach((good, q) -> result.put(good, q * fraction));
        return Map.copyOf(result);
    }

    private static Map<String, Double> copyQuantities(Map<String, Double> source) {
        source.forEach((good, q) -> {
            if (good == null || good.isBlank() || q == null || !Double.isFinite(q) || q < 0)
                throw new IllegalArgumentException("Invalid good quantity: " + good);
        });
        return Map.copyOf(source);
    }
}
