package com.rideshare.ride.kafka;

import com.rideshare.ride.dto.RideEventPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RideEventProducer {

    private static final String TOPIC = "ride-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(RideEventPayload event) {
        kafkaTemplate.send(TOPIC, event.getRideId().toString(), event);
        log.debug("Published ride event: rideId={}, status={}", event.getRideId(), event.getStatus());
    }
}
