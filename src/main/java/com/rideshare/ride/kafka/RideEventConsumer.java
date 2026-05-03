package com.rideshare.ride.kafka;

import com.rideshare.ride.dto.RideEventPayload;
import com.rideshare.ride.model.RideEvent;
import com.rideshare.ride.repository.RideEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Service
@RequiredArgsConstructor
public class RideEventConsumer {

    private final RideEventRepository rideEventRepository;

    @KafkaListener(topics = "ride-events", groupId = "rideshare-group")
    @Transactional
    public void consume(RideEventPayload payload) {
        log.debug("Received ride event: rideId={}, status={}", payload.getRideId(), payload.getStatus());

        RideEvent event = RideEvent.builder()
                .rideId(payload.getRideId())
                .riderId(payload.getRiderId())
                .driverId(payload.getDriverId())
                .status(payload.getStatus())
                .metadata(payload.getMetadata())
                .createdAt(LocalDateTime.ofInstant(payload.getTimestamp(), ZoneId.systemDefault()))
                .build();

        rideEventRepository.save(event);
    }
}
