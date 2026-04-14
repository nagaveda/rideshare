package com.rideshare.driver.dto;

import com.rideshare.driver.model.DriverStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverProfileResponse {

    private UUID userId;
    private String email;
    private String firstName;
    private String lastName;
    private String phone;
    private String licenseNumber;
    private String vehicleMake;
    private String vehicleModel;
    private Integer vehicleYear;
    private String vehicleColor;
    private String licensePlate;
    private DriverStatus status;
    private java.math.BigDecimal rating;
    private Integer totalRides;
}
