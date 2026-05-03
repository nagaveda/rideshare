package com.rideshare.ride.dto;

import com.rideshare.ride.model.RideStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RideEventPayload {

    private UUID rideId;
    private UUID riderId;
    private UUID driverId;
    private RideStatus status;
    private String metadata;
    private Instant timestamp;
}
