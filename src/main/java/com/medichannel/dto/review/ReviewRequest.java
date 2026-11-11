package com.medichannel.dto.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record ReviewRequest(

        @Min(value = 1, message = "Rating must be between 1 and 5.")
        @Max(value = 5, message = "Rating must be between 1 and 5.")
        int rating,

        @Size(max = 1000, message = "Review comment must not exceed 1000 characters.")
        String comment

) {
}
