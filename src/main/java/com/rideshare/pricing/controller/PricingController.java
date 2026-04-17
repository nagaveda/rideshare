package com.rideshare.pricing.controller;

import com.rideshare.common.dto.ApiResponse;
import com.rideshare.pricing.dto.FareEstimate;
import com.rideshare.pricing.dto.FareEstimateRequest;
import com.rideshare.pricing.service.FareCalculator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rides")
@RequiredArgsConstructor
public class PricingController {

    private final FareCalculator fareCalculator;

    @PostMapping("/estimate")
    public ResponseEntity<ApiResponse<FareEstimate>> estimateFare(
            @Valid @RequestBody FareEstimateRequest request) {

        FareEstimate estimate = fareCalculator.estimate(
                request.getPickupLongitude(), request.getPickupLatitude(),
                request.getDropoffLongitude(), request.getDropoffLatitude()
        );

        return ResponseEntity.ok(ApiResponse.ok(estimate));
    }
}
