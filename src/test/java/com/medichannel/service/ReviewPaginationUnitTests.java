package com.medichannel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.medichannel.controller.ReviewController;
import com.medichannel.dto.common.PagedResponse;
import com.medichannel.dto.review.DoctorReviewsResponse;
import com.medichannel.dto.review.ReviewRequest;
import com.medichannel.dto.review.ReviewResponse;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Review;
import com.medichannel.entity.User;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ForbiddenOperationException;
import com.medichannel.exception.GlobalExceptionHandler;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.ConsultationSummaryRepository;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.ReviewRepository;
import com.medichannel.repository.UserRepository;

public class ReviewPaginationUnitTests {

    private ReviewRepository reviewRepository;
    private AppointmentRepository appointmentRepository;
    private ConsultationSummaryRepository summaryRepository;
    private DoctorRepository doctorRepository;
    private AppointmentAccessPolicy accessPolicy;
    private NotificationService notificationService;
    private AuditLogService auditLogService;
    private UserRepository userRepository;
    private ReviewService reviewService;

    private UUID doctorId;
    private Doctor testDoctor;
    private UUID patientUserId;
    private UUID otherPatientUserId;
    private Patient testPatient;
    private Appointment completedAppointment;

    @BeforeEach
    void setUp() throws Exception {
        reviewRepository = mock(ReviewRepository.class);
        appointmentRepository = mock(AppointmentRepository.class);
        summaryRepository = mock(ConsultationSummaryRepository.class);
        doctorRepository = mock(DoctorRepository.class);
        accessPolicy = mock(AppointmentAccessPolicy.class);
        notificationService = mock(NotificationService.class);
        auditLogService = mock(AuditLogService.class);
        userRepository = mock(UserRepository.class);

        reviewService = new ReviewService(
                reviewRepository,
                appointmentRepository,
                summaryRepository,
                doctorRepository,
                accessPolicy,
                notificationService,
                auditLogService,
                userRepository
        );

        doctorId = UUID.randomUUID();
        testDoctor = mock(Doctor.class);
        when(testDoctor.getId()).thenReturn(doctorId);
        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(testDoctor));

        patientUserId = UUID.randomUUID();
        otherPatientUserId = UUID.randomUUID();
        testPatient = mock(Patient.class);

        completedAppointment = mock(Appointment.class);
        UUID appointmentId = UUID.randomUUID();
        when(completedAppointment.getId()).thenReturn(appointmentId);
        when(completedAppointment.getStatus()).thenReturn(AppointmentStatus.COMPLETED);
        when(completedAppointment.getPatient()).thenReturn(testPatient);

        DoctorSession session = mock(DoctorSession.class);
        when(session.getDoctor()).thenReturn(testDoctor);
        when(completedAppointment.getSession()).thenReturn(session);

        User doctorUser = mock(User.class);
        when(doctorUser.getId()).thenReturn(UUID.randomUUID());
        when(testDoctor.getUser()).thenReturn(doctorUser);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(completedAppointment));
        when(summaryRepository.existsByAppointment_Id(appointmentId)).thenReturn(true);
    }

    private Review createMockReview(UUID id, int rating, String comment, Instant createdAt, String moderationStatus) throws Exception {
        Review review = new Review(
                completedAppointment,
                testDoctor,
                testPatient,
                rating,
                comment
        );

        Field idField = Review.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(review, id);

        Field createdAtField = Review.class.getDeclaredField("createdAt");
        createdAtField.setAccessible(true);
        createdAtField.set(review, createdAt);

        Field statusField = Review.class.getDeclaredField("moderationStatus");
        statusField.setAccessible(true);
        statusField.set(review, moderationStatus);

        return review;
    }

    private MockMvc buildMockMvc() {
        ReviewController controller = new ReviewController(reviewService);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType().equals(Jwt.class);
                    }

                    @Override
                    public Object resolveArgument(
                            MethodParameter parameter,
                            ModelAndViewContainer mavContainer,
                            NativeWebRequest webRequest,
                            WebDataBinderFactory binderFactory
                    ) {
                        Jwt jwt = mock(Jwt.class);
                        when(jwt.getSubject()).thenReturn(patientUserId.toString());
                        return jwt;
                    }
                })
                .build();
    }

    @Test
    void test1_defaultFirstPage() {
        Page<Review> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(reviewRepository.findForModeration(null, null, null, PageRequest.of(0, 20))).thenReturn(emptyPage);

        PagedResponse<ReviewResponse> response = reviewService.getForModeration(null, null, null, 0, 20);

        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isTrue();
        assertThat(response.totalElements()).isEqualTo(0);
        assertThat(response.totalPages()).isEqualTo(0);
    }

    @Test
    void test2_customPageAndSize() {
        Page<Review> customPage = new PageImpl<>(List.of(), PageRequest.of(2, 10), 35);
        when(reviewRepository.findForModeration(null, null, null, PageRequest.of(2, 10))).thenReturn(customPage);

        PagedResponse<ReviewResponse> response = reviewService.getForModeration(null, null, null, 2, 10);

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(35);
        assertThat(response.totalPages()).isEqualTo(4);
    }

    @Test
    void test3_negativePageRejected() throws Exception {
        MockMvc mvc = buildMockMvc();
        mvc.perform(get("/api/v1/reviews/manage?page=-1&size=20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Page index must not be negative."));

        mvc.perform(get("/api/v1/doctors/" + doctorId + "/reviews?page=-1&size=20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Page index must not be negative."));
    }

    @Test
    void test4_zeroOrNegativeSizeRejected() throws Exception {
        MockMvc mvc = buildMockMvc();
        mvc.perform(get("/api/v1/reviews/manage?page=0&size=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Page size must be greater than zero."));

        mvc.perform(get("/api/v1/doctors/" + doctorId + "/reviews?page=0&size=-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Page size must be greater than zero."));
    }

    @Test
    void test5_sizeAbove100Rejected() throws Exception {
        MockMvc mvc = buildMockMvc();
        mvc.perform(get("/api/v1/reviews/manage?page=0&size=101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Page size must not exceed 100."));

        mvc.perform(get("/api/v1/doctors/" + doctorId + "/reviews?page=0&size=150"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Page size must not exceed 100."));
    }

    @Test
    void test6_newestFirstDeterministicOrdering() throws Exception {
        Instant t1 = Instant.parse("2026-11-20T10:00:00Z");
        Instant t2 = Instant.parse("2026-11-21T10:00:00Z");
        Instant t3 = Instant.parse("2026-11-22T10:00:00Z");

        Review r1 = createMockReview(UUID.randomUUID(), 3, "Oldest", t1, "APPROVED");
        Review r2 = createMockReview(UUID.randomUUID(), 4, "Middle", t2, "APPROVED");
        Review r3 = createMockReview(UUID.randomUUID(), 5, "Newest", t3, "APPROVED");

        Page<Review> page = new PageImpl<>(List.of(r3, r2, r1), PageRequest.of(0, 20), 3);
        when(reviewRepository.findApprovedByDoctorId(eq(doctorId), any(Pageable.class))).thenReturn(page);
        when(reviewRepository.countByDoctor_IdAndModerationStatus(doctorId, "APPROVED")).thenReturn(3L);
        when(reviewRepository.averageRatingByDoctorId(doctorId)).thenReturn(4.0);

        DoctorReviewsResponse response = reviewService.getDoctorReviews(doctorId, 0, 20);

        assertThat(response.reviews()).hasSize(3);
        assertThat(response.reviews().get(0).comment()).isEqualTo("Newest");
        assertThat(response.reviews().get(1).comment()).isEqualTo("Middle");
        assertThat(response.reviews().get(2).comment()).isEqualTo("Oldest");
    }

    @Test
    void test7_noOverlapBetweenPages() throws Exception {
        List<Review> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(createMockReview(UUID.randomUUID(), 5, "Review " + i, Instant.now().minusSeconds(i * 100), "APPROVED"));
        }

        Page<Review> page0 = new PageImpl<>(all.subList(0, 2), PageRequest.of(0, 2), 5);
        Page<Review> page1 = new PageImpl<>(all.subList(2, 4), PageRequest.of(1, 2), 5);

        when(reviewRepository.findForModeration(null, null, null, PageRequest.of(0, 2))).thenReturn(page0);
        when(reviewRepository.findForModeration(null, null, null, PageRequest.of(1, 2))).thenReturn(page1);

        PagedResponse<ReviewResponse> r0 = reviewService.getForModeration(null, null, null, 0, 2);
        PagedResponse<ReviewResponse> r1 = reviewService.getForModeration(null, null, null, 1, 2);

        List<UUID> ids0 = r0.content().stream().map(ReviewResponse::reviewId).toList();
        List<UUID> ids1 = r1.content().stream().map(ReviewResponse::reviewId).toList();

        assertThat(ids0).doesNotContainAnyElementsOf(ids1);
    }

    @Test
    void test8_beyondFinalPageReturnsSafeEmptyContent() {
        Page<Review> beyondPage = new PageImpl<>(List.of(), PageRequest.of(5, 20), 10);
        when(reviewRepository.findForModeration(null, null, null, PageRequest.of(5, 20))).thenReturn(beyondPage);

        PagedResponse<ReviewResponse> response = reviewService.getForModeration(null, null, null, 5, 20);

        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isEqualTo(5);
        assertThat(response.totalElements()).isEqualTo(10);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.last()).isTrue();
    }

    @Test
    void test9_emptyDatasetReturnsValidMetadata() {
        Page<Review> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(reviewRepository.findApprovedByDoctorId(eq(doctorId), any(Pageable.class))).thenReturn(emptyPage);
        when(reviewRepository.countByDoctor_IdAndModerationStatus(doctorId, "APPROVED")).thenReturn(0L);
        when(reviewRepository.averageRatingByDoctorId(doctorId)).thenReturn(null);

        DoctorReviewsResponse response = reviewService.getDoctorReviews(doctorId, 0, 20);

        assertThat(response.reviews()).isEmpty();
        assertThat(response.reviewCount()).isEqualTo(0L);
        assertThat(response.averageRating()).isEqualTo(0.0);
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.totalPages()).isEqualTo(0);
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isTrue();
    }

    @Test
    void test10_doctorFilterBeforePagination() {
        Page<Review> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(reviewRepository.findForModeration(null, doctorId, null, PageRequest.of(0, 20))).thenReturn(page);

        reviewService.getForModeration(null, doctorId, null, 0, 20);

        ArgumentCaptor<UUID> doctorCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(reviewRepository).findForModeration(eq(null), doctorCaptor.capture(), eq(null), any(Pageable.class));
        assertThat(doctorCaptor.getValue()).isEqualTo(doctorId);
    }

    @Test
    void test11_ratingFilterBeforePagination() {
        Page<Review> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(reviewRepository.findForModeration(null, null, 5, PageRequest.of(0, 20))).thenReturn(page);

        reviewService.getForModeration(null, null, 5, 0, 20);

        ArgumentCaptor<Integer> ratingCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(reviewRepository).findForModeration(eq(null), eq(null), ratingCaptor.capture(), any(Pageable.class));
        assertThat(ratingCaptor.getValue()).isEqualTo(5);

        assertThatThrownBy(() -> reviewService.getForModeration(null, null, 6, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Rating must be between 1 and 5.");
    }

    @Test
    void test12_visibilityBeforePagination() {
        Page<Review> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(reviewRepository.findApprovedByDoctorId(eq(doctorId), any(Pageable.class))).thenReturn(page);
        when(reviewRepository.countByDoctor_IdAndModerationStatus(doctorId, "APPROVED")).thenReturn(0L);

        reviewService.getDoctorReviews(doctorId, 0, 20);

        verify(reviewRepository).findApprovedByDoctorId(eq(doctorId), any(Pageable.class));
        verify(reviewRepository).countByDoctor_IdAndModerationStatus(doctorId, "APPROVED");
    }

    @Test
    void test13_patientCannotReviewAnotherPatientAppointment() {
        org.mockito.Mockito.doThrow(new ForbiddenOperationException("Access denied."))
                .when(accessPolicy).requirePatientOwner(completedAppointment, otherPatientUserId);

        assertThatThrownBy(() -> reviewService.create(otherPatientUserId, completedAppointment.getId(), new ReviewRequest(5, "Good")))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void test14_duplicateReviewRemainsBlocked() {
        when(reviewRepository.existsByAppointment_Id(completedAppointment.getId())).thenReturn(true);

        assertThatThrownBy(() -> reviewService.create(patientUserId, completedAppointment.getId(), new ReviewRequest(5, "Repeat")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Feedback has already been submitted for this appointment.");
    }

    @Test
    void test15_ineligibleAppointmentRemainsBlocked() {
        when(completedAppointment.getStatus()).thenReturn(AppointmentStatus.BOOKED);

        assertThatThrownBy(() -> reviewService.create(patientUserId, completedAppointment.getId(), new ReviewRequest(5, "Too early")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Feedback can only be submitted for a completed consultation.");
    }

    @Test
    void test16_aggregateRatingUsesFullDatasetNotVisiblePage() throws Exception {
        List<Review> visibleFive = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            visibleFive.add(createMockReview(UUID.randomUUID(), 5, "Great " + i, Instant.now(), "APPROVED"));
        }

        Page<Review> page = new PageImpl<>(visibleFive, PageRequest.of(0, 5), 100);
        when(reviewRepository.findApprovedByDoctorId(eq(doctorId), eq(PageRequest.of(0, 5)))).thenReturn(page);
        when(reviewRepository.countByDoctor_IdAndModerationStatus(doctorId, "APPROVED")).thenReturn(100L);
        when(reviewRepository.averageRatingByDoctorId(doctorId)).thenReturn(4.15);

        DoctorReviewsResponse response = reviewService.getDoctorReviews(doctorId, 0, 5);

        assertThat(response.reviewCount()).isEqualTo(100L);
        assertThat(response.averageRating()).isEqualTo(4.15);
        assertThat(response.reviews()).hasSize(5);
    }

    @Test
    void test17_zeroReviewAggregateIsSafe() {
        Page<Review> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(reviewRepository.findApprovedByDoctorId(eq(doctorId), any(Pageable.class))).thenReturn(empty);
        when(reviewRepository.countByDoctor_IdAndModerationStatus(doctorId, "APPROVED")).thenReturn(0L);
        when(reviewRepository.averageRatingByDoctorId(doctorId)).thenReturn(null);

        DoctorReviewsResponse response = reviewService.getDoctorReviews(doctorId, 0, 20);

        assertThat(response.reviewCount()).isEqualTo(0L);
        assertThat(response.averageRating()).isEqualTo(0.0);
    }

    @Test
    void test18_responseExposesNoPatientOrClinicalData() {
        RecordComponent[] components = ReviewResponse.class.getRecordComponents();
        List<String> names = List.of(components).stream().map(RecordComponent::getName).toList();

        assertThat(names).containsExactlyInAnyOrder(
                "reviewId",
                "appointmentId",
                "doctorId",
                "rating",
                "comment",
                "moderationStatus",
                "createdAt"
        );

        assertThat(names).doesNotContain(
                "patientEmail",
                "patientPhone",
                "patientNic",
                "patientName",
                "diagnosis",
                "prescription",
                "notes",
                "reason"
        );
    }

    @Test
    void test19_backwardCompatibilityVerified() throws Exception {
        DoctorReviewsResponse legacyObj = new DoctorReviewsResponse(
                doctorId,
                4.5,
                10L,
                List.of()
        );
        assertThat(legacyObj.doctorId()).isEqualTo(doctorId);
        assertThat(legacyObj.averageRating()).isEqualTo(4.5);
        assertThat(legacyObj.reviewCount()).isEqualTo(10L);
        assertThat(legacyObj.reviews()).isEmpty();
        assertThat(legacyObj.page()).isEqualTo(0);
        assertThat(legacyObj.totalPages()).isEqualTo(1);
    }
}
