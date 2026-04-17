package com.rideshare.location.kafka;

import com.rideshare.location.dto.LocationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocationProducer {

    private static final String TOPIC = "driver-location-updates";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(LocationEvent event) {
        kafkaTemplate.send(TOPIC, event.getDriverId().toString(), event);
        log.debug("Published location update for driver {}", event.getDriverId());
    }
}
