package com.bbmurloc.victoriaeconomics.server.workforce.employment;

import com.bbmurloc.victoriaeconomics.common.definition.occupation.OccupationRegistry;
import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.production.port.WorkforcePort;

import java.util.*;
import java.util.function.*;

/**
 * Employment owns position admission/reservations. Equipment only limits participation.
 */
public final class EmploymentService implements WorkforcePort {
    private final EmploymentRegistry employment;
    private final BuildingRegistry buildings;
    private final OccupationRegistry occupations;
    private final BiPredicate<UUID, String> qualification;
    private final Consumer<EmploymentRegistry.State> save;
    private final Object lock;

    public EmploymentService(EmploymentRegistry employment, BuildingRegistry buildings, OccupationRegistry occupations,
                             BiPredicate<UUID, String> qualification, Consumer<EmploymentRegistry.State> save, Object lock) {
        this.employment = employment;
        this.buildings = buildings;
        this.occupations = occupations;
        this.qualification = qualification;
        this.save = save;
        this.lock = lock;
    }

    private EconomicBuilding require(UUID id) {
        var b = buildings.get(id);
        if (b == null) throw new IllegalArgumentException("Unknown building: " + id);
        return b;
    }

    private <T> T mutate(Function<EmploymentRegistry, T> action) {
        synchronized (lock) {
            EmploymentRegistry next = new EmploymentRegistry();
            next.replace(employment.state());
            T result = action.apply(next);
            if (!next.state().equals(employment.state())) {
                save.accept(next.state());
                employment.replace(next.state());
            }
            return result;
        }
    }

    public JobReservation reservePosition(UUID employee, UUID building, String occupation) {
        return mutate(next -> {
            admit(next, employee, building, occupation);
            var reservation = new JobReservation(UUID.randomUUID(), employee, building, occupation);
            next.reserve(reservation);
            return reservation;
        });
    }

    public void cancelPositionReservation(UUID reservation) {
        mutate(next -> {
            next.cancelReservation(reservation);
            return null;
        });
    }

    public EmploymentRecord hire(UUID employee, UUID building, String occupation) {
        return mutate(next -> {
            admit(next, employee, building, occupation);
            var record = new EmploymentRecord(employee, building, occupation);
            next.add(record);
            return record;
        });
    }

    public EmploymentRecord acceptPosition(UUID reservationId) {
        return mutate(next -> {
            var reservation = next.reservation(reservationId);
            if (reservation == null) throw new IllegalArgumentException("Unknown job reservation");
            next.cancelReservation(reservationId);
            admit(next, reservation.employeeId(), reservation.buildingId(), reservation.occupationId());
            var record = new EmploymentRecord(reservation.employeeId(), reservation.buildingId(), reservation.occupationId());
            next.add(record);
            return record;
        });
    }

    private void admit(EmploymentRegistry next, UUID employee, UUID building, String occupation) {
        if (occupations.get(occupation) == null)
            throw new IllegalArgumentException("Unknown occupation: " + occupation);
        if (!qualification.test(employee, occupation))
            throw new IllegalStateException("NPC qualification unavailable or insufficient");
        if (next.isEmployed(employee) || next.reservations().stream().anyMatch(r -> r.employeeId().equals(employee))) {
            throw new IllegalStateException("Employee already employed or reserved");
        }
        EconomicBuilding b = require(building);
        if (b.getStatus() != BuildingStatus.ACTIVE) throw new IllegalStateException("Building is stopped");
        int capacity = b.getProductionDepartment().getRecipe().requiredWorkers().getOrDefault(occupation, 0);
        if (next.count(building, occupation) + next.reservedPositions(building, occupation) >= capacity) {
            throw new IllegalStateException("Effective PM position capacity exhausted: " + occupation);
        }
    }

    public EmploymentRecord fire(UUID employee) {
        return mutate(next -> {
            var record = next.getByEmployee(employee);
            if (record == null) return null;
            var batch = require(record.buildingId()).getProductionDepartment().getActiveBatch();
            if (batch != null && !batch.isEnded() && batch.getConfiguration().activeEmployeesByOccupation().values().stream().anyMatch(ids -> ids.contains(employee))) {
                next.requestDismissal(employee);
            } else next.remove(employee);
            return record;
        });
    }

    @Override
    public Collection<EmploymentRecord> effectiveEmployment(UUID building) {
        synchronized (lock) {
            return employment.getAll().stream().filter(e -> e.buildingId().equals(building) && !employment.dismissalPending(e.employeeId())).toList();
        }
    }

    @Override
    public boolean qualified(UUID employee, String occupation) {
        return qualification.test(employee, occupation);
    }

    @Override
    public boolean reconcile(UUID building, Map<String, Integer> demand) {
        return mutate(next -> {
            if (require(building).getProductionDepartment().hasActiveBatch())
                throw new IllegalStateException("Cannot reconcile protected participation");
            // Reservation order is acceptance order; cancel excess reservations before employee reconciliation.
            Map<String, Integer> retained = new HashMap<>();
            for (var reservation : next.reservations()) {
                if (!reservation.buildingId().equals(building)) continue;
                int free = Math.max(0, demand.getOrDefault(reservation.occupationId(), 0) - next.count(building, reservation.occupationId()));
                int count = retained.merge(reservation.occupationId(), 1, Integer::sum);
                if (count > free) next.cancelReservation(reservation.id());
            }
            for (UUID employee : next.pendingDismissals()) {
                if (next.getByEmployee(employee).buildingId().equals(building)) next.remove(employee);
            }
            // Company identity and NPC consent are not implemented. Never silently transfer or auto-fire.
            return next.getStaffingCounts(building).entrySet().stream()
                    .allMatch(e -> e.getValue() <= demand.getOrDefault(e.getKey(), 0));
        });
    }

    public EmploymentRecord getEmployment(UUID employee) {
        synchronized (lock) {
            return employment.getByEmployee(employee);
        }
    }

    public int countWorkers(UUID building, String occupation) {
        synchronized (lock) {
            return employment.count(building, occupation);
        }
    }
}
