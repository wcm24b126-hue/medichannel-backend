package com.medichannel.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.review.DoctorReviewsResponse;
import com.medichannel.dto.review.ReviewRequest;
import com.medichannel.dto.review.ReviewResponse;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.Review;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.NotificationType;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.ReviewRepository;

@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final AppointmentRepository appointmentRepository;
    private final DoctorRepository doctorRepository;
    private final AppointmentAccessPolicy accessPolicy;
    private final NotificationService notificationService;

    public ReviewService(
            ReviewRepository reviewRepository,
            AppointmentRepository appointmentRepository,
            DoctorRepository doctorRepository,
            AppointmentAccessPolicy accessPolicy,
            NotificationService notificationService
    ) {
        this.reviewRepository = reviewRepository;
        this.appointmentRepository = appointmentRepository;
        this.doctorRepository = doctorRepository;
        this.accessPolicy = accessPolicy;
        this.notificationService = notificationService;
    }

    @Transactional
    public ReviewResponse create(
            UUID patientUserId,
            UUID appointmentId,
            ReviewRequest request
    ) {
        Appointment appointment = appointmentRepository
                .findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment was not found."
                ));

        accessPolicy.requirePatientOwner(appointment, patientUserId);

        if (appointment.getStatus() != AppointmentStatus.COMPLETED) {
            throw new ConflictException(
                    "Feedback can only be submitted for a completed consultation."
            );
        }

        if (reviewRepository.existsByAppointment_Id(appointmentId)) {
            throw new ConflictException(
                    "Feedback has already been submitted for this appointment."
            );
        }

        if (request == null || request.rating() < 1 || request.rating() > 5) {
            throw new IllegalArgumentException(
                    "Rating must be between 1 and 5."
            );
        }

        String comment = normalizeComment(request.comment());

        Review review = new Review(
                appointment,
                appointment.getSession().getDoctor(),
                appointment.getPatient(),
                request.rating(),
                comment
        );

        try {
            Review saved = reviewRepository.saveAndFlush(review);

            notificationService.create(
                    appointment.getSession().getDoctor().getUser().getId(),
                    NotificationType.REVIEW_RECEIVED,
                    "New patient feedback",
                    "A patient submitted a "
                            + request.rating()
                            + "-star review for a completed consultation.",
                    appointment.getId()
            );

            return toResponse(saved);

        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException(
                    "Feedback has already been submitted for this appointment."
            );
        }
    }

    @Transactional(readOnly = true)
    public DoctorReviewsResponse getDoctorReviews(UUID doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Doctor was not found."
                ));

        List<Review> reviews = reviewRepository
                .findByDoctor_IdOrderByCreatedAtDesc(doctorId);

        Double average = reviewRepository.averageRatingByDoctorId(doctorId);

        return new DoctorReviewsResponse(
                doctor.getId(),
                average == null
                        ? 0.0
                        : BigDecimal.valueOf(average)
                                .setScale(2, RoundingMode.HALF_UP)
                                .doubleValue(),
                reviews.size(),
                reviews.stream().map(this::toResponse).toList()
        );
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> getMyReviews(UUID patientUserId) {
        return reviewRepository
                .findByPatient_User_IdOrderByCreatedAtDesc(patientUserId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private String normalizeComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }

        String normalized = comment.trim();

        if (normalized.length() > 1000) {
            throw new IllegalArgumentException(
                    "Review comment must not exceed 1000 characters."
            );
        }

        return normalized;
    }

    private ReviewResponse toResponse(Review review) {
        return new ReviewResponse(
                review.getId(),
                review.getAppointment().getId(),
                review.getDoctor().getId(),
                review.getRating(),
                review.getComment(),
                review.getCreatedAt()
        );
    }
}
