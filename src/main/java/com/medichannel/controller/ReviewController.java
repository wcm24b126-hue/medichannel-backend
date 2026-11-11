package com.medichannel.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.medichannel.dto.review.DoctorReviewsResponse;
import com.medichannel.dto.review.ReviewRequest;
import com.medichannel.dto.review.ReviewResponse;
import com.medichannel.service.ReviewService;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    // Patient feedback for their own completed appointment.
    @PostMapping("/appointments/{appointmentId}/review")
    public ResponseEntity<ReviewResponse> submitReview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId,
            @Valid @RequestBody ReviewRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(reviewService.create(userId, appointmentId, request));
    }

    @GetMapping("/reviews/me")
    public ResponseEntity<List<ReviewResponse>> getMyReviews(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(reviewService.getMyReviews(userId));
    }

    // Public doctor reviews and average rating.
    @GetMapping("/doctors/{doctorId}/reviews")
    public ResponseEntity<DoctorReviewsResponse> getDoctorReviews(
            @PathVariable UUID doctorId
    ) {
        return ResponseEntity.ok(
                reviewService.getDoctorReviews(doctorId)
        );
    }
}
