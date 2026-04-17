package com.rideshare.pricing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FareEstimate {

    private BigDecimal baseFare;
    private double surgeMultiplier;
    private BigDecimal estimatedTotal;
    private double distanceKm;
    private double durationMinutes;
    private String zone;
}
