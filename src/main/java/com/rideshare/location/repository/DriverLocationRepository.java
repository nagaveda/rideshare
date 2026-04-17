package com.rideshare.location.repository;

import com.rideshare.location.model.DriverLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DriverLocationRepository extends JpaRepository<DriverLocation, UUID> {

    Optional<DriverLocation> findByDriverId(UUID driverId);

    @Modifying
    @Query(value = """
            INSERT INTO driver_locations (id, driver_id, location, heading, speed, updated_at)
            VALUES (gen_random_uuid(), :driverId, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), :heading, :speed, NOW())
            ON CONFLICT (driver_id)
            DO UPDATE SET location = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326),
                          heading = :heading,
                          speed = :speed,
                          updated_at = NOW()
            """, nativeQuery = true)
    void upsertLocation(@Param("driverId") UUID driverId,
                         @Param("lng") double longitude,
                         @Param("lat") double latitude,
                         @Param("heading") double heading,
                         @Param("speed") double speed);

    @Query(value = """
            SELECT dl.* FROM driver_locations dl
            JOIN driver_profiles dp ON dp.user_id = dl.driver_id
            WHERE ST_DWithin(dl.location::geography, ST_MakePoint(:lng, :lat)::geography, :radiusMeters)
              AND dp.status = 'AVAILABLE'
            ORDER BY ST_Distance(dl.location::geography, ST_MakePoint(:lng, :lat)::geography)
            """, nativeQuery = true)
    List<DriverLocation> findAvailableDriversWithinRadius(@Param("lng") double longitude,
                                                          @Param("lat") double latitude,
                                                          @Param("radiusMeters") double radiusMeters);
}
