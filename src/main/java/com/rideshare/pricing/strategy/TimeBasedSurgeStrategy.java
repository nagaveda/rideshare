package com.rideshare.pricing.strategy;

import com.rideshare.pricing.dto.SurgeMetrics;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;

@Component
public class TimeBasedSurgeStrategy implements SurgePricingStrategy {

    // Peak hours and their multipliers
    private static final int MORNING_PEAK_START = 7;
    private static final int MORNING_PEAK_END = 9;
    private static final double MORNING_MULTIPLIER = 1.5;

    private static final int EVENING_PEAK_START = 17;
    private static final int EVENING_PEAK_END = 19;
    private static final double EVENING_MULTIPLIER = 1.5;

    private static final int LATE_NIGHT_START = 22;
    private static final int LATE_NIGHT_END = 4;
    private static final double LATE_NIGHT_MULTIPLIER = 1.3;

    @Override
    public double calculateMultiplier(String zone, SurgeMetrics metrics) {
        int hour = LocalTime.now(ZoneId.systemDefault()).getHour();

        if (hour >= MORNING_PEAK_START && hour < MORNING_PEAK_END) {
            return MORNING_MULTIPLIER;
        }

        if (hour >= EVENING_PEAK_START && hour < EVENING_PEAK_END) {
            return EVENING_MULTIPLIER;
        }

        if (hour >= LATE_NIGHT_START || hour < LATE_NIGHT_END) {
            return LATE_NIGHT_MULTIPLIER;
        }

        return 1.0;
    }
}
