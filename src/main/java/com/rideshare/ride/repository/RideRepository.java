package com.rideshare.ride.repository;

import com.rideshare.ride.model.Ride;
import com.rideshare.ride.model.RideStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RideRepository extends JpaRepository<Ride, UUID> {

    Optional<Ride> findFirstByRiderIdAndStatusNotInOrderByRequestedAtDesc(UUID riderId, List<RideStatus> excludedStatuses);

    Optional<Ride> findFirstByDriverIdAndStatusNotInOrderByRequestedAtDesc(UUID driverId, List<RideStatus> excludedStatuses);

    Page<Ride> findByRiderIdOrderByRequestedAtDesc(UUID riderId, Pageable pageable);

    Page<Ride> findByDriverIdOrderByRequestedAtDesc(UUID driverId, Pageable pageable);

    Optional<Ride> findByRiderIdAndIdempotencyKey(UUID riderId, String idempotencyKey);
}
