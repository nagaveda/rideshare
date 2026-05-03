package com.rideshare.ride.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum RideStatus {
    REQUESTED,
    DRIVER_ASSIGNED,
    DRIVER_EN_ROUTE,
    ARRIVED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    private static final Map<RideStatus, Set<RideStatus>> ALLOWED_TRANSITIONS = Map.of(
            REQUESTED, EnumSet.of(DRIVER_ASSIGNED, CANCELLED),
            DRIVER_ASSIGNED, EnumSet.of(DRIVER_EN_ROUTE, CANCELLED),
            DRIVER_EN_ROUTE, EnumSet.of(ARRIVED, CANCELLED),
            ARRIVED, EnumSet.of(IN_PROGRESS, CANCELLED),
            IN_PROGRESS, EnumSet.of(COMPLETED),
            COMPLETED, EnumSet.noneOf(RideStatus.class),
            CANCELLED, EnumSet.noneOf(RideStatus.class)
    );

    public boolean canTransitionTo(RideStatus next) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, EnumSet.noneOf(RideStatus.class)).contains(next);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
