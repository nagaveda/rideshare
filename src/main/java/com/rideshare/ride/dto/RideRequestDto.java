package com.rideshare.ride.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RideRequestDto {

    @NotNull
    @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0")
    private Double pickupLatitude;

    @NotNull
    @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
    private Double pickupLongitude;

    @NotNull
    @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0")
    private Double dropoffLatitude;

    @NotNull
    @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
    private Double dropoffLongitude;

    @Size(max = 500)
    private String pickupAddress;

    @Size(max = 500)
    private String dropoffAddress;
}
