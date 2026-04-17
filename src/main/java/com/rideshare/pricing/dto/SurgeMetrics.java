package com.rideshare.pricing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SurgeMetrics {

    private long demandCount;
    private long supplyCount;
    private Instant timestamp;
}
