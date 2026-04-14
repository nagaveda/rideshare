package com.rideshare.driver.service;

import com.rideshare.common.exception.ResourceNotFoundException;
import com.rideshare.driver.dto.DriverProfileResponse;
import com.rideshare.driver.dto.UpdateDriverProfileRequest;
import com.rideshare.driver.model.DriverProfile;
import com.rideshare.driver.model.DriverStatus;
import com.rideshare.driver.repository.DriverProfileRepository;
import com.rideshare.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DriverService {

    private final DriverProfileRepository driverProfileRepository;

    @Transactional(readOnly = true)
    public DriverProfileResponse getProfile(UUID userId) {
        DriverProfile profile = findByUserId(userId);
        return toResponse(profile);
    }

    @Transactional
    public DriverProfileResponse updateProfile(UUID userId, UpdateDriverProfileRequest request) {
        DriverProfile profile = findByUserId(userId);

        if (request.getLicenseNumber() != null) profile.setLicenseNumber(request.getLicenseNumber());
        if (request.getVehicleMake() != null) profile.setVehicleMake(request.getVehicleMake());
        if (request.getVehicleModel() != null) profile.setVehicleModel(request.getVehicleModel());
        if (request.getVehicleYear() != null) profile.setVehicleYear(request.getVehicleYear());
        if (request.getVehicleColor() != null) profile.setVehicleColor(request.getVehicleColor());
        if (request.getLicensePlate() != null) profile.setLicensePlate(request.getLicensePlate());

        profile = driverProfileRepository.save(profile);
        return toResponse(profile);
    }

    @Transactional
    public DriverProfileResponse updateStatus(UUID userId, DriverStatus status) {
        DriverProfile profile = findByUserId(userId);
        profile.setStatus(status);
        profile = driverProfileRepository.save(profile);
        return toResponse(profile);
    }

    private DriverProfile findByUserId(UUID userId) {
        return driverProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("DriverProfile", "userId", userId));
    }

    private DriverProfileResponse toResponse(DriverProfile profile) {
        User user = profile.getUser();
        return DriverProfileResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phone(user.getPhone())
                .licenseNumber(profile.getLicenseNumber())
                .vehicleMake(profile.getVehicleMake())
                .vehicleModel(profile.getVehicleModel())
                .vehicleYear(profile.getVehicleYear())
                .vehicleColor(profile.getVehicleColor())
                .licensePlate(profile.getLicensePlate())
                .status(profile.getStatus())
                .rating(profile.getRating())
                .totalRides(profile.getTotalRides())
                .build();
    }
}
