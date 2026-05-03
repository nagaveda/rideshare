package com.rideshare.ride.controller;

import com.rideshare.common.dto.ApiResponse;
import com.rideshare.location.model.DriverLocation;
import com.rideshare.ride.dto.CancelRideRequest;
import com.rideshare.ride.dto.DriverLocationResponse;
import com.rideshare.ride.dto.RideRequestDto;
import com.rideshare.ride.dto.RideResponse;
import com.rideshare.ride.model.Ride;
import com.rideshare.ride.service.RideService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rides")
@RequiredArgsConstructor
public class RideController {

    private final RideService rideService;

    @PostMapping("/request")
    @PreAuthorize("hasRole('RIDER')")
    public ResponseEntity<ApiResponse<RideResponse>> requestRide(
            @AuthenticationPrincipal UUID riderId,
            @Valid @RequestBody RideRequestDto request) {
        Ride ride = rideService.requestRide(riderId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(RideResponse.from(ride)));
    }

    @PutMapping("/{rideId}/accept")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<ApiResponse<RideResponse>> acceptRide(
            @PathVariable UUID rideId,
            @AuthenticationPrincipal UUID driverId) {
        Ride ride = rideService.acceptRide(rideId, driverId);
        return ResponseEntity.ok(ApiResponse.ok(RideResponse.from(ride)));
    }

    @PutMapping("/{rideId}/en-route")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<ApiResponse<RideResponse>> startEnRoute(
            @PathVariable UUID rideId,
            @AuthenticationPrincipal UUID driverId) {
        Ride ride = rideService.startEnRoute(rideId, driverId);
        return ResponseEntity.ok(ApiResponse.ok(RideResponse.from(ride)));
    }

    @PutMapping("/{rideId}/arrived")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<ApiResponse<RideResponse>> arrivedAtPickup(
            @PathVariable UUID rideId,
            @AuthenticationPrincipal UUID driverId) {
        Ride ride = rideService.arrivedAtPickup(rideId, driverId);
        return ResponseEntity.ok(ApiResponse.ok(RideResponse.from(ride)));
    }

    @PutMapping("/{rideId}/start")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<ApiResponse<RideResponse>> startRide(
            @PathVariable UUID rideId,
            @AuthenticationPrincipal UUID driverId) {
        Ride ride = rideService.startRide(rideId, driverId);
        return ResponseEntity.ok(ApiResponse.ok(RideResponse.from(ride)));
    }

    @PutMapping("/{rideId}/complete")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<ApiResponse<RideResponse>> completeRide(
            @PathVariable UUID rideId,
            @AuthenticationPrincipal UUID driverId) {
        Ride ride = rideService.completeRide(rideId, driverId);
        return ResponseEntity.ok(ApiResponse.ok(RideResponse.from(ride)));
    }

    @PutMapping("/{rideId}/cancel")
    public ResponseEntity<ApiResponse<RideResponse>> cancelRide(
            @PathVariable UUID rideId,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody CancelRideRequest request) {
        Ride ride = rideService.cancelRide(rideId, userId, request.getReason());
        return ResponseEntity.ok(ApiResponse.ok(RideResponse.from(ride)));
    }

    @GetMapping("/{rideId}")
    public ResponseEntity<ApiResponse<RideResponse>> getRide(
            @PathVariable UUID rideId,
            @AuthenticationPrincipal UUID userId) {
        Ride ride = rideService.getRide(rideId, userId);
        return ResponseEntity.ok(ApiResponse.ok(RideResponse.from(ride)));
    }

    @GetMapping("/{rideId}/driver-location")
    @PreAuthorize("hasRole('RIDER')")
    public ResponseEntity<ApiResponse<DriverLocationResponse>> getDriverLocation(
            @PathVariable UUID rideId,
            @AuthenticationPrincipal UUID riderId) {
        DriverLocation location = rideService.getDriverLocationForRide(rideId, riderId);
        DriverLocationResponse response = DriverLocationResponse.builder()
                .latitude(location.getLocation().getY())
                .longitude(location.getLocation().getX())
                .heading(location.getHeading())
                .speed(location.getSpeed())
                .updatedAt(location.getUpdatedAt())
                .build();
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<Page<RideResponse>>> getHistory(
            Authentication authentication,
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        boolean isDriver = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DRIVER"));
        Page<Ride> rides = isDriver
                ? rideService.getDriverHistory(userId, pageable)
                : rideService.getRiderHistory(userId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(rides.map(RideResponse::from)));
    }
}
