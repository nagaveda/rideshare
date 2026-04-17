package com.rideshare.pricing.service;

import com.rideshare.pricing.dto.SurgeMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class SurgeService {

    private static final String DEMAND_KEY_PREFIX = "surge:zone:%s:demand";
    private static final String SUPPLY_KEY_PREFIX = "surge:zone:%s:supply";
    private static final Duration COUNTER_TTL = Duration.ofSeconds(60);

    // Grid-based zone resolution: ~1km x ~1km cells
    private static final double ZONE_GRID_SIZE = 0.01; // ~1.1km at equator

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Increment demand counter for the zone containing these coordinates.
     * Called when a rider requests a ride.
     */
    public void incrementDemand(double longitude, double latitude) {
        String zone = resolveZone(longitude, latitude);
        String key = String.format(DEMAND_KEY_PREFIX, zone);
        redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, COUNTER_TTL);
        log.debug("Incremented demand for zone {}", zone);
    }

    /**
     * Update supply counter for the zone containing these coordinates.
     * Called by the location consumer when a driver sends a location update.
     */
    public void updateSupply(String zone, long count) {
        String key = String.format(SUPPLY_KEY_PREFIX, zone);
        redisTemplate.opsForValue().set(key, count, COUNTER_TTL);
        log.debug("Updated supply for zone {} to {}", zone, count);
    }

    /**
     * Get current demand and supply metrics for a zone.
     */
    public SurgeMetrics getMetrics(String zone) {
        String demandKey = String.format(DEMAND_KEY_PREFIX, zone);
        String supplyKey = String.format(SUPPLY_KEY_PREFIX, zone);

        long demand = getLongValue(demandKey);
        long supply = getLongValue(supplyKey);

        return SurgeMetrics.builder()
                .demandCount(demand)
                .supplyCount(supply)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Resolve a (longitude, latitude) pair to a zone identifier.
     * Uses a simple grid-based approach: floor coordinates to the nearest grid cell.
     */
    public String resolveZone(double longitude, double latitude) {
        long gridLng = Math.round(Math.floor(longitude / ZONE_GRID_SIZE));
        long gridLat = Math.round(Math.floor(latitude / ZONE_GRID_SIZE));
        return gridLng + ":" + gridLat;
    }

    private long getLongValue(String key) {
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
