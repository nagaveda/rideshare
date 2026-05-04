package com.rideshare.rating.dto;

import com.rideshare.rating.model.Rating;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RatingResponse {

    private UUID ratingId;
    private UUID rideId;
    private UUID raterId;
    private UUID rateeId;
    private Integer score;
    private String comment;
    private LocalDateTime createdAt;

    public static RatingResponse from(Rating rating) {
        return RatingResponse.builder()
                .ratingId(rating.getId())
                .rideId(rating.getRideId())
                .raterId(rating.getRaterId())
                .rateeId(rating.getRateeId())
                .score(rating.getScore())
                .comment(rating.getComment())
                .createdAt(rating.getCreatedAt())
                .build();
    }
}
