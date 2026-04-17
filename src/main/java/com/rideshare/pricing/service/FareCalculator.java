package com.rideshare.pricing.service;

import com.rideshare.pricing.dto.FareEstimate;
import com.rideshare.pricing.dto.SurgeMetrics;
import com.rideshare.pricing.strategy.SurgePricingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Service
public class FareCalculator {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double AVERAGE_SPEED_KMH = 30.0; // Assumed city driving speed

    private final SurgeService surgeService;
    private final SurgePricingStrategy surgePricingStrategy;

    @Value("${pricing.base-rate:2.50}")
    private double baseRate;

    @Value("${pricing.per-km:1.50}")
    private double perKm;

    @Value("${pricing.per-minute:0.25}")
    private double perMinute;

    @Value("${pricing.minimum-fare:5.00}")
    private double minimumFare;

    public FareCalculator(SurgeService surgeService,
                          @Qualifier("demandSupplyRatioStrategy") SurgePricingStrategy surgePricingStrategy) {
        this.surgeService = surgeService;
        this.surgePricingStrategy = surgePricingStrategy;
    }

    /**
     * Calculate fare estimate for a trip between two points.
     */
    public FareEstimate estimate(double pickupLng, double pickupLat,
                                  double dropoffLng, double dropoffLat) {
        double distanceKm = haversineDistance(pickupLat, pickupLng, dropoffLat, dropoffLng);
        double durationMinutes = (distanceKm / AVERAGE_SPEED_KMH) * 60.0;

        String zone = surgeService.resolveZone(pickupLng, pickupLat);
        SurgeMetrics metrics = surgeService.getMetrics(zone);
        double surgeMultiplier = surgePricingStrategy.calculateMultiplier(zone, metrics);

        BigDecimal baseFare = BigDecimal.valueOf(baseRate)
                .add(BigDecimal.valueOf(perKm * distanceKm))
                .add(BigDecimal.valueOf(perMinute * durationMinutes))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal estimatedTotal = baseFare
                .multiply(BigDecimal.valueOf(surgeMultiplier))
                .setScale(2, RoundingMode.HALF_UP);

        // Enforce minimum fare
        if (estimatedTotal.compareTo(BigDecimal.valueOf(minimumFare)) < 0) {
            estimatedTotal = BigDecimal.valueOf(minimumFare).setScale(2, RoundingMode.HALF_UP);
        }

        return FareEstimate.builder()
                .baseFare(baseFare)
                .surgeMultiplier(surgeMultiplier)
                .estimatedTotal(estimatedTotal)
                .distanceKm(Math.round(distanceKm * 100.0) / 100.0)
                .durationMinutes(Math.round(durationMinutes * 100.0) / 100.0)
                .zone(zone)
                .build();
    }

    /**
     * Haversine formula: calculate straight-line distance between two lat/lng points on Earth.
     * Returns distance in kilometers.
     */
    private double haversineDistance(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_KM * c;
    }
}
