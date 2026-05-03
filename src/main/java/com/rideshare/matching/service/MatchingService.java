package com.rideshare.matching.service;

import com.rideshare.driver.model.DriverStatus;
import com.rideshare.driver.repository.DriverProfileRepository;
import com.rideshare.location.model.DriverLocation;
import com.rideshare.location.repository.DriverLocationRepository;
import com.rideshare.location.service.DriverLocationRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingService {

    private static final double[] RADIUS_EXPANSION_KM = {3.0, 5.0, 10.0};

    private final DriverLocationRedisService redisService;
    private final DriverLocationRepository driverLocationRepository;
    private final DriverProfileRepository driverProfileRepository;

    /**
     * Find the closest available driver to the given pickup location.
     * Tries radii in order (3km → 5km → 10km). Excludes any drivers already skipped for this ride.
     *
     * @return UUID of selected driver, or empty if no drivers found in any radius.
     */
    public Optional<UUID> findNearestDriver(double pickupLng, double pickupLat, Set<UUID> skippedDrivers) {
        for (double radiusKm : RADIUS_EXPANSION_KM) {
            List<UUID> candidates = findCandidates(pickupLng, pickupLat, radiusKm);

            for (UUID driverId : candidates) {
                if (skippedDrivers.contains(driverId)) {
                    continue;
                }
                if (isDriverAvailable(driverId)) {
                    log.info("Matched driver {} at radius {}km", driverId, radiusKm);
                    return Optional.of(driverId);
                }
            }

            log.debug("No available drivers within {}km, expanding radius", radiusKm);
        }

        log.info("No drivers available within max radius for pickup ({}, {})", pickupLat, pickupLng);
        return Optional.empty();
    }

    /**
     * Find candidate driver IDs within the given radius.
     * Primary: Redis GEO. Fallback: PostGIS direct query.
     */
    private List<UUID> findCandidates(double lng, double lat, double radiusKm) {
        if (redisService.isAvailable()) {
            try {
                return redisService.findDriversWithinRadius(lng, lat, radiusKm);
            } catch (Exception e) {
                log.warn("Redis GEO query failed, falling back to PostGIS: {}", e.getMessage());
            }
        }

        // PostGIS-only fallback: directly query driver_locations with status filter
        double radiusMeters = radiusKm * 1000.0;
        List<DriverLocation> drivers = driverLocationRepository.findAvailableDriversWithinRadius(lng, lat, radiusMeters);
        return drivers.stream().map(DriverLocation::getDriverId).toList();
    }

    private boolean isDriverAvailable(UUID driverId) {
        return driverProfileRepository.findByUserId(driverId)
                .map(p -> p.getStatus() == DriverStatus.AVAILABLE)
                .orElse(false);
    }
}
