package com.rideshare.ride.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverLocationResponse {

    private double latitude;
    private double longitude;
    private Double heading;
    private Double speed;
    private LocalDateTime updatedAt;
}
