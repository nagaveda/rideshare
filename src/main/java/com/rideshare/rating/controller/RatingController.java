package com.rideshare.rating.controller;

import com.rideshare.common.dto.ApiResponse;
import com.rideshare.rating.dto.RatingRequest;
import com.rideshare.rating.dto.RatingResponse;
import com.rideshare.rating.model.Rating;
import com.rideshare.rating.service.RatingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rides")
@RequiredArgsConstructor
public class RatingController {

    private final RatingService ratingService;

    @PostMapping("/{rideId}/rate")
    public ResponseEntity<ApiResponse<RatingResponse>> submitRating(
            @PathVariable UUID rideId,
            @AuthenticationPrincipal UUID raterId,
            @Valid @RequestBody RatingRequest request) {
        Rating rating = ratingService.submitRating(rideId, raterId, request.getScore(), request.getComment());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(RatingResponse.from(rating)));
    }
}
