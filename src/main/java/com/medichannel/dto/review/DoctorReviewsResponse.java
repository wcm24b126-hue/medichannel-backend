package com.medichannel.dto.review;

import java.util.List;
import java.util.UUID;

public record DoctorReviewsResponse(

        UUID doctorId,
        double averageRating,
        long reviewCount,
        List<ReviewResponse> reviews,
        int page,
        int size,
        int totalPages,
        boolean first,
        boolean last

) {

    public DoctorReviewsResponse(
            UUID doctorId,
            double averageRating,
            long reviewCount,
            List<ReviewResponse> reviews
    ) {
        this(
                doctorId,
                averageRating,
                reviewCount,
                reviews,
                0,
                reviews != null ? reviews.size() : 0,
                1,
                true,
                true
        );
    }
}
