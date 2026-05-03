package com.rideshare.ride.service;

import com.rideshare.common.exception.BadRequestException;
import com.rideshare.common.exception.ResourceNotFoundException;
import com.rideshare.common.exception.UnauthorizedException;
import com.rideshare.common.util.GeometryUtils;
import com.rideshare.driver.model.DriverProfile;
import com.rideshare.driver.model.DriverStatus;
import com.rideshare.driver.repository.DriverProfileRepository;
import com.rideshare.location.model.DriverLocation;
import com.rideshare.location.repository.DriverLocationRepository;
import com.rideshare.matching.service.MatchingService;
import com.rideshare.pricing.dto.FareEstimate;
import com.rideshare.pricing.service.FareCalculator;
import com.rideshare.pricing.service.SurgeService;
import com.rideshare.ride.dto.RideEventPayload;
import com.rideshare.ride.dto.RideRequestDto;
import com.rideshare.ride.kafka.RideEventProducer;
import com.rideshare.ride.model.Ride;
import com.rideshare.ride.model.RideStatus;
import com.rideshare.ride.repository.RideRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RideService {

    private static final List<RideStatus> TERMINAL_STATUSES = List.of(RideStatus.COMPLETED, RideStatus.CANCELLED);

    private final RideRepository rideRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final DriverLocationRepository driverLocationRepository;
    private final MatchingService matchingService;
    private final FareCalculator fareCalculator;
    private final SurgeService surgeService;
    private final RideEventProducer rideEventProducer;

    @Transactional
    public Ride requestRide(UUID riderId, RideRequestDto request) {
        // Reject if rider already has an active ride
        Optional<Ride> activeRide = rideRepository.findFirstByRiderIdAndStatusNotInOrderByRequestedAtDesc(
                riderId, TERMINAL_STATUSES);
        if (activeRide.isPresent()) {
            throw new BadRequestException("Rider already has an active ride");
        }

        FareEstimate estimate = fareCalculator.estimate(
                request.getPickupLongitude(), request.getPickupLatitude(),
                request.getDropoffLongitude(), request.getDropoffLatitude());

        // Increment surge demand for the pickup zone
        surgeService.incrementDemand(request.getPickupLongitude(), request.getPickupLatitude());

        Ride ride = Ride.builder()
                .riderId(riderId)
                .status(RideStatus.REQUESTED)
                .pickupLocation(GeometryUtils.point(request.getPickupLongitude(), request.getPickupLatitude()))
                .dropoffLocation(GeometryUtils.point(request.getDropoffLongitude(), request.getDropoffLatitude()))
                .pickupAddress(request.getPickupAddress())
                .dropoffAddress(request.getDropoffAddress())
                .estimatedFare(estimate.getEstimatedTotal())
                .surgeMultiplier(BigDecimal.valueOf(estimate.getSurgeMultiplier()))
                .distanceKm(BigDecimal.valueOf(estimate.getDistanceKm()))
                .durationMinutes(BigDecimal.valueOf(estimate.getDurationMinutes()))
                .requestedAt(LocalDateTime.now())
                .build();

        ride = rideRepository.save(ride);

        // Try to find a driver immediately. If none found, ride remains in REQUESTED state.
        Optional<UUID> matchedDriverId = matchingService.findNearestDriver(
                request.getPickupLongitude(), request.getPickupLatitude(), Collections.emptySet());

        publishEvent(ride, null);

        if (matchedDriverId.isEmpty()) {
            log.info("No drivers found for ride {}. Will need retry/expansion.", ride.getId());
            return ride;
        }

        // Auto-assign the matched driver. In a real system, this would notify the driver
        // and wait for accept/decline. For MVP, we treat the match as an assignment offer.
        return assignDriver(ride, matchedDriverId.get());
    }

    @Transactional
    public Ride acceptRide(UUID rideId, UUID driverId) {
        Ride ride = findRide(rideId);
        validateAssignedDriver(ride, driverId);
        transition(ride, RideStatus.DRIVER_ASSIGNED);

        ride.setAcceptedAt(LocalDateTime.now());
        setDriverStatus(driverId, DriverStatus.BUSY);

        ride = rideRepository.save(ride);
        publishEvent(ride, null);
        return ride;
    }

    @Transactional
    public Ride startEnRoute(UUID rideId, UUID driverId) {
        Ride ride = findRide(rideId);
        validateAssignedDriver(ride, driverId);
        transition(ride, RideStatus.DRIVER_EN_ROUTE);
        ride = rideRepository.save(ride);
        publishEvent(ride, null);
        return ride;
    }

    @Transactional
    public Ride arrivedAtPickup(UUID rideId, UUID driverId) {
        Ride ride = findRide(rideId);
        validateAssignedDriver(ride, driverId);
        transition(ride, RideStatus.ARRIVED);
        ride = rideRepository.save(ride);
        publishEvent(ride, null);
        return ride;
    }

    @Transactional
    public Ride startRide(UUID rideId, UUID driverId) {
        Ride ride = findRide(rideId);
        validateAssignedDriver(ride, driverId);
        transition(ride, RideStatus.IN_PROGRESS);
        ride.setStartedAt(LocalDateTime.now());
        ride = rideRepository.save(ride);
        publishEvent(ride, null);
        return ride;
    }

    @Transactional
    public Ride completeRide(UUID rideId, UUID driverId) {
        Ride ride = findRide(rideId);
        validateAssignedDriver(ride, driverId);
        transition(ride, RideStatus.COMPLETED);

        ride.setCompletedAt(LocalDateTime.now());
        // For MVP, actual fare equals estimated fare.
        ride.setActualFare(ride.getEstimatedFare());
        setDriverStatus(driverId, DriverStatus.AVAILABLE);

        ride = rideRepository.save(ride);
        publishEvent(ride, null);
        return ride;
    }

    @Transactional
    public Ride cancelRide(UUID rideId, UUID userId, String reason) {
        Ride ride = findRide(rideId);

        // Only the rider or assigned driver can cancel
        if (!ride.getRiderId().equals(userId) && !userId.equals(ride.getDriverId())) {
            throw new UnauthorizedException("Only the rider or assigned driver can cancel this ride");
        }

        if (!ride.getStatus().canTransitionTo(RideStatus.CANCELLED)) {
            throw new BadRequestException("Ride cannot be cancelled from status " + ride.getStatus());
        }

        ride.setStatus(RideStatus.CANCELLED);
        ride.setCancelledAt(LocalDateTime.now());
        ride.setCancellationReason(reason);

        // Release driver if one was assigned
        if (ride.getDriverId() != null) {
            setDriverStatus(ride.getDriverId(), DriverStatus.AVAILABLE);
        }

        ride = rideRepository.save(ride);
        publishEvent(ride, reason != null ? "{\"reason\":\"" + reason.replace("\"", "\\\"") + "\"}" : null);
        return ride;
    }

    @Transactional(readOnly = true)
    public Ride getRide(UUID rideId, UUID userId) {
        Ride ride = findRide(rideId);
        if (!ride.getRiderId().equals(userId) && !userId.equals(ride.getDriverId())) {
            throw new UnauthorizedException("Not authorized to view this ride");
        }
        return ride;
    }

    @Transactional(readOnly = true)
    public DriverLocation getDriverLocationForRide(UUID rideId, UUID riderId) {
        Ride ride = findRide(rideId);
        if (!ride.getRiderId().equals(riderId)) {
            throw new UnauthorizedException("Only the rider can poll driver location");
        }
        if (ride.getDriverId() == null) {
            throw new BadRequestException("No driver assigned to this ride yet");
        }
        if (ride.getStatus().isTerminal()) {
            throw new BadRequestException("Ride is no longer active");
        }
        return driverLocationRepository.findByDriverId(ride.getDriverId())
                .orElseThrow(() -> new ResourceNotFoundException("DriverLocation", "driverId", ride.getDriverId()));
    }

    @Transactional(readOnly = true)
    public Page<Ride> getRiderHistory(UUID riderId, Pageable pageable) {
        return rideRepository.findByRiderIdOrderByRequestedAtDesc(riderId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Ride> getDriverHistory(UUID driverId, Pageable pageable) {
        return rideRepository.findByDriverIdOrderByRequestedAtDesc(driverId, pageable);
    }

    private Ride assignDriver(Ride ride, UUID driverId) {
        ride.setDriverId(driverId);
        ride.setStatus(RideStatus.DRIVER_ASSIGNED);
        ride.setAcceptedAt(LocalDateTime.now());
        setDriverStatus(driverId, DriverStatus.BUSY);
        ride = rideRepository.save(ride);
        publishEvent(ride, null);
        return ride;
    }

    private Ride findRide(UUID rideId) {
        return rideRepository.findById(rideId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride", "id", rideId));
    }

    private void validateAssignedDriver(Ride ride, UUID driverId) {
        if (ride.getDriverId() == null || !ride.getDriverId().equals(driverId)) {
            throw new UnauthorizedException("Driver is not assigned to this ride");
        }
    }

    private void transition(Ride ride, RideStatus next) {
        if (!ride.getStatus().canTransitionTo(next)) {
            throw new BadRequestException(
                    "Invalid state transition: " + ride.getStatus() + " → " + next);
        }
        ride.setStatus(next);
    }

    private void setDriverStatus(UUID driverId, DriverStatus status) {
        DriverProfile profile = driverProfileRepository.findByUserId(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("DriverProfile", "userId", driverId));
        profile.setStatus(status);
        driverProfileRepository.save(profile);
    }

    private void publishEvent(Ride ride, String metadata) {
        RideEventPayload payload = RideEventPayload.builder()
                .rideId(ride.getId())
                .riderId(ride.getRiderId())
                .driverId(ride.getDriverId())
                .status(ride.getStatus())
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
        rideEventProducer.publish(payload);
    }
}
