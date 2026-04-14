package com.rideshare.driver.repository;

import com.rideshare.driver.model.DriverProfile;
import com.rideshare.driver.model.DriverStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DriverProfileRepository extends JpaRepository<DriverProfile, UUID> {

    Optional<DriverProfile> findByUserId(UUID userId);

    long countByStatus(DriverStatus status);
}
