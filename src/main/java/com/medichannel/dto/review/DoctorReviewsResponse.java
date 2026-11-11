package com.medichannel.dto.review;

import java.util.List;
import java.util.UUID;

public record DoctorReviewsResponse(

        UUID doctorId,
        double averageRating,
        long reviewCount,
        List<ReviewResponse> reviews

) {
}
