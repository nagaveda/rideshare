package com.rideshare.location.kafka;

import com.rideshare.location.dto.LocationEvent;
import com.rideshare.location.repository.DriverLocationRepository;
import com.rideshare.location.service.DriverLocationRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocationConsumer {

    private final DriverLocationRepository driverLocationRepository;
    private final DriverLocationRedisService redisService;

    @KafkaListener(topics = "driver-location-updates", groupId = "rideshare-group")
    @Transactional
    public void consume(LocationEvent event) {
        log.debug("Received location update for driver {}", event.getDriverId());

        // Write to PostGIS (upsert)
        driverLocationRepository.upsertLocation(
                event.getDriverId(),
                event.getLongitude(),
                event.getLatitude(),
                event.getHeading(),
                event.getSpeed()
        );

        // Write to Redis GEO + reset TTL
        try {
            redisService.updateDriverLocation(
                    event.getDriverId(),
                    event.getLongitude(),
                    event.getLatitude()
            );
        } catch (Exception e) {
            log.warn("Failed to update Redis GEO for driver {}: {}", event.getDriverId(), e.getMessage());
            // PostGIS write already succeeded — Redis failure is non-fatal
        }
    }
}
