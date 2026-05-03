package com.rideshare.ride.repository;

import com.rideshare.ride.model.RideRoutePoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RideRoutePointRepository extends JpaRepository<RideRoutePoint, UUID> {

    List<RideRoutePoint> findByRideIdOrderByRecordedAtAsc(UUID rideId);
}
