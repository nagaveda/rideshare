package com.rideshare.pricing.strategy;

import com.rideshare.pricing.dto.SurgeMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DemandSupplyRatioStrategy implements SurgePricingStrategy {

    @Value("${pricing.surge.max-multiplier:3.0}")
    private double maxMultiplier;

    @Value("${pricing.surge.min-supply:1}")
    private long minSupply;

    @Override
    public double calculateMultiplier(String zone, SurgeMetrics metrics) {
        if (metrics.getSupplyCount() <= 0) {
            // No drivers available — cap at max to avoid division by zero
            return metrics.getDemandCount() > 0 ? maxMultiplier : 1.0;
        }

        double ratio = (double) metrics.getDemandCount() / Math.max(metrics.getSupplyCount(), minSupply);

        if (ratio <= 1.0) {
            return 1.0; // Supply meets or exceeds demand — no surge
        }

        return Math.min(ratio, maxMultiplier);
    }
}
