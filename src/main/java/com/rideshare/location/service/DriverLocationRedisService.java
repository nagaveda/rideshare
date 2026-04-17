package com.rideshare.location.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DriverLocationRedisService {

    private static final String GEO_KEY = "drivers:active";
    private static final String ACTIVE_KEY_PREFIX = "driver:active:";
    private static final Duration ACTIVE_TTL = Duration.ofSeconds(30);

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Add or update a driver's position in the GEO set and reset their active TTL.
     */
    public void updateDriverLocation(UUID driverId, double longitude, double latitude) {
        redisTemplate.opsForGeo().add(GEO_KEY, new Point(longitude, latitude), driverId.toString());
        redisTemplate.expire(ACTIVE_KEY_PREFIX + driverId, ACTIVE_TTL);
        redisTemplate.opsForValue().set(ACTIVE_KEY_PREFIX + driverId, "1", ACTIVE_TTL);
    }

    /**
     * Find driver IDs within the given radius (in kilometers) from a point.
     */
    public List<UUID> findDriversWithinRadius(double longitude, double latitude, double radiusKm) {
        GeoResults<RedisGeoCommands.GeoLocation<Object>> results = redisTemplate.opsForGeo().radius(
                GEO_KEY,
                new Circle(new Point(longitude, latitude), new Distance(radiusKm, RedisGeoCommands.DistanceUnit.KILOMETERS)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .sortAscending()
        );

        if (results == null) {
            return Collections.emptyList();
        }

        return results.getContent().stream()
                .map(r -> UUID.fromString(r.getContent().getName().toString()))
                .toList();
    }

    /**
     * Remove a driver from the GEO set (e.g., when they go offline).
     */
    public void removeDriver(UUID driverId) {
        redisTemplate.opsForGeo().remove(GEO_KEY, driverId.toString());
        redisTemplate.delete(ACTIVE_KEY_PREFIX + driverId);
    }

    /**
     * Check if Redis is reachable.
     */
    public boolean isAvailable() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            return true;
        } catch (Exception e) {
            log.warn("Redis is unavailable: {}", e.getMessage());
            return false;
        }
    }
}
