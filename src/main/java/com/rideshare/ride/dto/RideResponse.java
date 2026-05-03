package com.rideshare.ride.dto;

import com.rideshare.ride.model.Ride;
import com.rideshare.ride.model.RideStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RideResponse {

    private UUID rideId;
    private UUID riderId;
    private UUID driverId;
    private RideStatus status;
    private double pickupLatitude;
    private double pickupLongitude;
    private double dropoffLatitude;
    private double dropoffLongitude;
    private String pickupAddress;
    private String dropoffAddress;
    private BigDecimal estimatedFare;
    private BigDecimal actualFare;
    private BigDecimal surgeMultiplier;
    private BigDecimal distanceKm;
    private BigDecimal durationMinutes;
    private LocalDateTime requestedAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;
    private String cancellationReason;

    public static RideResponse from(Ride ride) {
        return RideResponse.builder()
                .rideId(ride.getId())
                .riderId(ride.getRiderId())
                .driverId(ride.getDriverId())
                .status(ride.getStatus())
                .pickupLatitude(ride.getPickupLocation().getY())
                .pickupLongitude(ride.getPickupLocation().getX())
                .dropoffLatitude(ride.getDropoffLocation().getY())
                .dropoffLongitude(ride.getDropoffLocation().getX())
                .pickupAddress(ride.getPickupAddress())
                .dropoffAddress(ride.getDropoffAddress())
                .estimatedFare(ride.getEstimatedFare())
                .actualFare(ride.getActualFare())
                .surgeMultiplier(ride.getSurgeMultiplier())
                .distanceKm(ride.getDistanceKm())
                .durationMinutes(ride.getDurationMinutes())
                .requestedAt(ride.getRequestedAt())
                .acceptedAt(ride.getAcceptedAt())
                .startedAt(ride.getStartedAt())
                .completedAt(ride.getCompletedAt())
                .cancelledAt(ride.getCancelledAt())
                .cancellationReason(ride.getCancellationReason())
                .build();
    }
}
