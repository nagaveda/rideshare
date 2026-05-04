package com.rideshare.payment.repository;

import com.rideshare.payment.model.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByRideId(UUID rideId);

    boolean existsByRideId(UUID rideId);

    Page<Payment> findByRiderIdOrderByCreatedAtDesc(UUID riderId, Pageable pageable);

    Page<Payment> findByDriverIdOrderByCreatedAtDesc(UUID driverId, Pageable pageable);
}
