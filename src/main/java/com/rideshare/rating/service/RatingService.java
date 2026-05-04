package com.rideshare.rating.service;

import com.rideshare.common.exception.BadRequestException;
import com.rideshare.common.exception.ResourceNotFoundException;
import com.rideshare.common.exception.UnauthorizedException;
import com.rideshare.driver.model.DriverProfile;
import com.rideshare.driver.repository.DriverProfileRepository;
import com.rideshare.rating.model.Rating;
import com.rideshare.rating.repository.RatingRepository;
import com.rideshare.ride.model.Ride;
import com.rideshare.ride.model.RideStatus;
import com.rideshare.ride.repository.RideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RatingService {

    private final RatingRepository ratingRepository;
    private final RideRepository rideRepository;
    private final DriverProfileRepository driverProfileRepository;

    @Transactional
    public Rating submitRating(UUID rideId, UUID raterId, int score, String comment) {
        if (score < 1 || score > 5) {
            throw new BadRequestException("Score must be between 1 and 5");
        }

        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride", "id", rideId));

        if (ride.getStatus() != RideStatus.COMPLETED) {
            throw new BadRequestException("Can only rate a completed ride");
        }

        UUID rateeId = resolveRatee(ride, raterId);

        if (ratingRepository.existsByRideIdAndRaterId(rideId, raterId)) {
            throw new BadRequestException("You have already rated this ride");
        }

        Rating rating = Rating.builder()
                .rideId(rideId)
                .raterId(raterId)
                .rateeId(rateeId)
                .score(score)
                .comment(comment)
                .createdAt(LocalDateTime.now())
                .build();

        rating = ratingRepository.save(rating);

        // Update running average on driver profile when the ratee is the driver.
        // Riders have no profile entity in this MVP, so rider ratings are stored but not aggregated.
        if (rateeId.equals(ride.getDriverId())) {
            updateDriverRunningAverage(rateeId, score);
        }

        log.info("Rating submitted: rideId={}, raterId={}, rateeId={}, score={}", rideId, raterId, rateeId, score);
        return rating;
    }

    private UUID resolveRatee(Ride ride, UUID raterId) {
        if (raterId.equals(ride.getRiderId())) {
            if (ride.getDriverId() == null) {
                throw new BadRequestException("Ride has no driver to rate");
            }
            return ride.getDriverId();
        }
        if (raterId.equals(ride.getDriverId())) {
            return ride.getRiderId();
        }
        throw new UnauthorizedException("Only the rider or driver of this ride can submit a rating");
    }

    private void updateDriverRunningAverage(UUID driverId, int newScore) {
        DriverProfile profile = driverProfileRepository.findByUserId(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("DriverProfile", "userId", driverId));

        BigDecimal currentAverage = profile.getRating() != null ? profile.getRating() : BigDecimal.ZERO;
        int currentCount = profile.getTotalRides() != null ? profile.getTotalRides() : 0;

        BigDecimal newCount = BigDecimal.valueOf(currentCount + 1L);
        BigDecimal weightedSum = currentAverage.multiply(BigDecimal.valueOf(currentCount))
                .add(BigDecimal.valueOf(newScore));
        BigDecimal newAverage = weightedSum.divide(newCount, 2, RoundingMode.HALF_UP);

        profile.setRating(newAverage);
        profile.setTotalRides(currentCount + 1);
        driverProfileRepository.save(profile);
    }
}
