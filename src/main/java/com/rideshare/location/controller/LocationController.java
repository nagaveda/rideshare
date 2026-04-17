package com.rideshare.location.controller;

import com.rideshare.common.dto.ApiResponse;
import com.rideshare.common.exception.BadRequestException;
import com.rideshare.driver.model.DriverProfile;
import com.rideshare.driver.model.DriverStatus;
import com.rideshare.driver.repository.DriverProfileRepository;
import com.rideshare.location.dto.LocationEvent;
import com.rideshare.location.dto.LocationUpdateRequest;
import com.rideshare.location.kafka.LocationProducer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/drivers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DRIVER')")
public class LocationController {

    private final LocationProducer locationProducer;
    private final DriverProfileRepository driverProfileRepository;

    @PostMapping("/location")
    public ResponseEntity<ApiResponse<Void>> updateLocation(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody LocationUpdateRequest request) {

        DriverProfile profile = driverProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("Driver profile not found"));

        if (profile.getStatus() == DriverStatus.OFFLINE) {
            throw new BadRequestException("Driver must be AVAILABLE or BUSY to send location updates");
        }

        LocationEvent event = LocationEvent.builder()
                .driverId(userId)
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .heading(request.getHeading() != null ? request.getHeading() : 0.0)
                .speed(request.getSpeed() != null ? request.getSpeed() : 0.0)
                .timestamp(Instant.now())
                .build();

        locationProducer.publish(event);

        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
