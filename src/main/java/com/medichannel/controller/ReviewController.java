package com.medichannel.controller;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.medichannel.dto.common.PagedResponse;
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

    // Public doctor reviews and average rating with server-side pagination.
    @GetMapping("/doctors/{doctorId}/reviews")
    public ResponseEntity<DoctorReviewsResponse> getDoctorReviews(
            @PathVariable UUID doctorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(
                reviewService.getDoctorReviews(doctorId, page, size)
        );
    }

    // Manager feedback moderation with server-side pagination.
    @GetMapping("/reviews/manage")
    public ResponseEntity<PagedResponse<ReviewResponse>> getFeedbackForModeration(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID doctorId,
            @RequestParam(required = false) Integer rating,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(
                reviewService.getForModeration(status, doctorId, rating, page, size)
        );
    }

    @PatchMapping("/reviews/{reviewId}/approve")
    public ResponseEntity<ReviewResponse> approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID reviewId,
            HttpServletRequest httpRequest
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(reviewService.moderate(
                reviewId,
                true,
                userId,
                clientIp(httpRequest)
        ));
    }

    @PatchMapping("/reviews/{reviewId}/hide")
    public ResponseEntity<ReviewResponse> hide(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID reviewId,
            HttpServletRequest httpRequest
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(reviewService.moderate(
                reviewId,
                false,
                userId,
                clientIp(httpRequest)
        ));
    }

    @DeleteMapping("/reviews/{reviewId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID reviewId,
            HttpServletRequest httpRequest
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        reviewService.delete(reviewId, userId, clientIp(httpRequest));
        return ResponseEntity.noContent().build();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
