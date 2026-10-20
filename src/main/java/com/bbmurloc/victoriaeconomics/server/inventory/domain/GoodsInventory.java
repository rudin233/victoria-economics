package com.bbmurloc.victoriaeconomics.server.inventory.domain;

import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionSettlement;
import java.util.*;

/** Integer goods authority for balances, capacity, exclusive reservations and settlement receipts. */
public final class GoodsInventory {
    public record Settlement(ProductionSettlement command) {
        public Settlement { Objects.requireNonNull(command); }
        public UUID batchId() { return command.batchId(); }
        public Map<String, Long> consumed() { return command.consumed(); }
        public Map<String, Long> produced() { return command.produced(); }
    }

    public record State(UUID location, long capacity, Map<String, Long> quantities,
                        Map<UUID, Map<String, Long>> reservations, Map<UUID, Settlement> settlements) {
        public State {
            Objects.requireNonNull(location);
            if (capacity < 0) throw new IllegalArgumentException("Negative storage capacity");
            quantities = checked(quantities, false);
            Map<UUID, Map<String, Long>> locks = new HashMap<>();
            reservations.forEach((id, amounts) -> locks.put(Objects.requireNonNull(id), checked(amounts, true)));
            reservations = Map.copyOf(locks);
            settlements = Map.copyOf(settlements);
            settlements.forEach((id, receipt) -> {
                if (!id.equals(receipt.batchId())) throw new IllegalArgumentException("Settlement key differs from its batch");
            });
        }
    }

    private final UUID location;
    private final long capacity;
    private final Map<String, Long> quantities;
    private final Map<UUID, Map<String, Long>> reservations;
    private final Map<UUID, Settlement> settlements;

    public GoodsInventory(UUID location, long capacity) { this(new State(location, capacity, Map.of(), Map.of(), Map.of())); }

    public GoodsInventory(State state) {
        location = state.location(); capacity = state.capacity();
        quantities = new HashMap<>(state.quantities());
        reservations = new HashMap<>(state.reservations());
        settlements = new HashMap<>(state.settlements());
        total(quantities);
        Set<String> goods = new HashSet<>(quantities.keySet());
        reservations.values().forEach(amounts -> goods.addAll(amounts.keySet()));
        for (String good : goods)
            if (reserved(good) > quantity(good)) throw new IllegalArgumentException("Reservation exceeds stock: " + good);
        for (UUID batch : reservations.keySet())
            if (settlements.containsKey(batch)) throw new IllegalArgumentException("Settled batch still holds materials");
    }

    public State state() { return new State(location, capacity, quantities, reservations, settlements); }
    public long quantity(String good) { return quantities.getOrDefault(good, 0L); }
    public long reserved(String good) {
        long result = 0;
        for (var amounts : reservations.values()) result = Math.addExact(result, amounts.getOrDefault(good, 0L));
        return result;
    }
    public long available(String good) { return Math.subtractExact(quantity(good), reserved(good)); }
    public long used() { return total(quantities); }
    public boolean overCapacity() { return used() > capacity; }

    public void deposit(Map<String, Long> amounts) {
        var additions = checked(amounts, true);
        Map<String, Long> next = new HashMap<>(quantities);
        additions.forEach((good, quantity) -> next.merge(good, quantity, Math::addExact));
        if (overCapacity() || total(next) > capacity) throw new IllegalStateException("Ordinary storage intake exceeds capacity: " + location);
        quantities.clear(); quantities.putAll(next);
    }

    public void withdraw(Map<String, Long> amounts) {
        var withdrawals = checked(amounts, true);
        withdrawals.forEach((good, quantity) -> {
            if (quantity > available(good)) throw new IllegalStateException("Insufficient available stock: " + good);
        });
        withdrawals.forEach((good, quantity) -> quantities.put(good, Math.subtractExact(quantity(good), quantity)));
    }

    public void reserve(UUID batch, Map<String, Long> inputs) {
        Objects.requireNonNull(batch);
        var commitment = checked(inputs, true);
        if (settlements.containsKey(batch)) throw new IllegalStateException("Batch has already settled");
        if (reservations.containsKey(batch)) {
            if (!reservations.get(batch).equals(commitment)) throw new IllegalStateException("Reservation id reused");
            return;
        }
        if (overCapacity()) throw new IllegalStateException("Overfull storage cannot start production");
        commitment.forEach((good, quantity) -> {
            if (quantity > available(good)) throw new IllegalStateException("Missing batch input: " + good);
        });
        reservations.put(batch, commitment);
    }

    public boolean hasReservation(UUID batch, Map<String, Long> inputs) { return inputs.equals(reservations.get(batch)); }
    public void release(UUID batch) { reservations.remove(batch); }
    public Settlement settlement(UUID batch) { return settlements.get(batch); }

    /** One durable command per BatchId, matched by outcome and complete frozen inputs/outputs. */
    public Settlement settle(ProductionSettlement command) {
        UUID batch = command.batchId();
        Settlement prior = settlements.get(batch);
        if (prior != null) {
            if (!prior.command().equals(command)) throw new IllegalStateException("Conflicting repeated settlement");
            return prior;
        }
        if (!hasReservation(batch, command.inputs())) throw new IllegalStateException("Batch material commitment not held: " + batch);
        Map<String, Long> next = new HashMap<>(quantities);
        command.consumed().forEach((good, quantity) -> next.put(good, Math.subtractExact(next.getOrDefault(good, 0L), quantity)));
        command.produced().forEach((good, quantity) -> next.merge(good, quantity, Math::addExact));
        checked(next, false);
        total(next); // Reject overflow before any mutation, while allowing a representable committed overflow of capacity.
        quantities.clear(); quantities.putAll(next);
        reservations.remove(batch);
        Settlement result = new Settlement(command);
        settlements.put(batch, result);
        return result;
    }

    private static long total(Map<String, Long> amounts) {
        long result = 0;
        for (long quantity : amounts.values()) result = Math.addExact(result, quantity);
        return result;
    }
    private static Map<String, Long> checked(Map<String, Long> source, boolean positive) {
        source.forEach((good, quantity) -> {
            if (good == null || good.isBlank() || quantity == null || quantity < 0 || (positive && quantity == 0))
                throw new IllegalArgumentException("Invalid integer good quantity: " + good);
        });
        return Map.copyOf(source);
    }
}
