package com.rideshare.pricing.strategy;

import com.rideshare.pricing.dto.SurgeMetrics;

public interface SurgePricingStrategy {

    /**
     * Calculate the surge multiplier for a given zone and current metrics.
     *
     * @param zone    the zone identifier
     * @param metrics current demand/supply metrics
     * @return surge multiplier (1.0 = no surge)
     */
    double calculateMultiplier(String zone, SurgeMetrics metrics);
}
