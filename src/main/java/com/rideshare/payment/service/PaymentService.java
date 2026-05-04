package com.rideshare.payment.service;

import com.rideshare.common.exception.BadRequestException;
import com.rideshare.payment.model.Payment;
import com.rideshare.payment.model.PaymentMethod;
import com.rideshare.payment.model.PaymentStatus;
import com.rideshare.payment.repository.PaymentRepository;
import com.rideshare.ride.model.Ride;
import com.rideshare.ride.model.RideStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;

    @Transactional
    public Payment createPaymentForRide(Ride ride) {
        if (ride.getStatus() != RideStatus.COMPLETED) {
            throw new BadRequestException("Cannot create payment for ride in status " + ride.getStatus());
        }
        if (paymentRepository.existsByRideId(ride.getId())) {
            log.debug("Payment already exists for ride {}, skipping creation", ride.getId());
            return paymentRepository.findByRideId(ride.getId()).orElseThrow();
        }

        BigDecimal total = ride.getActualFare();
        BigDecimal surgeMultiplier = ride.getSurgeMultiplier() != null ? ride.getSurgeMultiplier() : BigDecimal.ONE;
        BigDecimal baseFare = surgeMultiplier.compareTo(BigDecimal.ZERO) > 0
                ? total.divide(surgeMultiplier, 2, RoundingMode.HALF_UP)
                : total;
        BigDecimal surgeAmount = total.subtract(baseFare).max(BigDecimal.ZERO);

        Payment payment = Payment.builder()
                .rideId(ride.getId())
                .riderId(ride.getRiderId())
                .driverId(ride.getDriverId())
                .amount(total)
                .baseFare(baseFare)
                .surgeAmount(surgeAmount)
                .paymentMethod(PaymentMethod.CASH)
                .status(PaymentStatus.COMPLETED)
                .build();

        payment = paymentRepository.save(payment);
        log.info("Created payment {} for ride {} (amount={})", payment.getId(), ride.getId(), payment.getAmount());
        return payment;
    }

    @Transactional(readOnly = true)
    public Page<Payment> getRiderHistory(UUID riderId, Pageable pageable) {
        return paymentRepository.findByRiderIdOrderByCreatedAtDesc(riderId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Payment> getDriverHistory(UUID driverId, Pageable pageable) {
        return paymentRepository.findByDriverIdOrderByCreatedAtDesc(driverId, pageable);
    }
}
