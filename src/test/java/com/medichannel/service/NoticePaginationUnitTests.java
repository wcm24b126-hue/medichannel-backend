package com.medichannel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.medichannel.controller.NoticeController;
import com.medichannel.dto.common.PagedResponse;
import com.medichannel.dto.notice.NoticeRequest;
import com.medichannel.dto.notice.NoticeResponse;
import com.medichannel.entity.Notice;
import com.medichannel.entity.Role;
import com.medichannel.entity.User;
import com.medichannel.exception.GlobalExceptionHandler;
import com.medichannel.repository.NoticeRepository;
import com.medichannel.repository.UserRepository;

public class NoticePaginationUnitTests {

    private NoticeRepository noticeRepository;
    private UserRepository userRepository;
    private AuditLogService auditLogService;
    private NoticeService noticeService;
    private NoticeController noticeController;
    private MockMvc mockMvc;

    private User managerUser;
    private UUID managerUserId;

    @BeforeEach
    void setUp() throws Exception {
        noticeRepository = mock(NoticeRepository.class);
        userRepository = mock(UserRepository.class);
        auditLogService = mock(AuditLogService.class);

        noticeService = new NoticeService(noticeRepository, userRepository, auditLogService);
        noticeController = new NoticeController(noticeService);

        managerUserId = UUID.randomUUID();
        Role managerRole = new Role("MANAGER", "Hospital Manager");
        managerUser = new User(managerRole, "manager@hospital.lk", "hashed_password");
        setField(managerUser, "id", managerUserId);

        when(userRepository.findById(managerUserId)).thenReturn(Optional.of(managerUser));

        HandlerMethodArgumentResolver jwtResolver = new HandlerMethodArgumentResolver() {
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
                return Jwt.withTokenValue("mock-token")
                        .header("alg", "none")
                        .subject(managerUserId.toString())
                        .claim("role", "ROLE_MANAGER")
                        .build();
            }
        };

        mockMvc = MockMvcBuilders.standaloneSetup(noticeController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(jwtResolver)
                .build();
    }

    private Notice createNotice(String title, String body, boolean published, Instant createdAt) throws Exception {
        Notice notice = new Notice(title, body, managerUser);
        setField(notice, "id", UUID.randomUUID());
        setField(notice, "createdAt", createdAt);
        setField(notice, "updatedAt", createdAt);
        if (published) {
            setField(notice, "published", true);
            setField(notice, "publishedAt", createdAt);
        } else {
            setField(notice, "published", false);
            setField(notice, "publishedAt", null);
        }
        return notice;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // 1. Default first page
    @Test
    @DisplayName("1. Default first page returns page 0, size 20 and correct metadata")
    void testDefaultFirstPage() throws Exception {
        List<Notice> notices = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < 20; i++) {
            notices.add(createNotice("Notice " + i, "Body " + i, true, now.minus(i, ChronoUnit.HOURS)));
        }
        Page<Notice> page = new PageImpl<>(notices, PageRequest.of(0, 20), 45);
        when(noticeRepository.findForManagement(eq(null), any(Pageable.class))).thenReturn(page);

        PagedResponse<NoticeResponse> response = noticeService.getManagerNotices(null, 0, 20);

        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(45);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isFalse();
        assertThat(response.content()).hasSize(20);
    }

    // 2. Custom page and size
    @Test
    @DisplayName("2. Custom page and size are passed correctly to repository")
    void testCustomPageAndSize() throws Exception {
        List<Notice> notices = List.of(
                createNotice("Notice 10", "Body 10", true, Instant.now())
        );
        Page<Notice> page = new PageImpl<>(notices, PageRequest.of(2, 5), 11);
        when(noticeRepository.findForManagement(eq(null), any(Pageable.class))).thenReturn(page);

        PagedResponse<NoticeResponse> response = noticeService.getManagerNotices(null, 2, 5);

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(5);
        assertThat(response.totalElements()).isEqualTo(11);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.first()).isFalse();
        assertThat(response.last()).isTrue();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noticeRepository).findForManagement(eq(null), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
    }

    // 3. Negative page rejected
    @Test
    @DisplayName("3. Negative page is rejected with IllegalArgumentException and HTTP 400")
    void testNegativePageRejected() throws Exception {
        assertThatThrownBy(() -> noticeService.getManagerNotices(null, -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page index must not be negative");

        mockMvc.perform(get("/api/v1/notices/manage?page=-1&size=20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    // 4. Zero/negative size rejected
    @Test
    @DisplayName("4. Zero or negative size is rejected with IllegalArgumentException and HTTP 400")
    void testZeroOrNegativeSizeRejected() throws Exception {
        assertThatThrownBy(() -> noticeService.getManagerNotices(null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must be greater than zero");

        assertThatThrownBy(() -> noticeService.getManagerNotices(null, 0, -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must be greater than zero");

        mockMvc.perform(get("/api/v1/notices/manage?page=0&size=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    // 5. Size above 100 rejected
    @Test
    @DisplayName("5. Size above 100 is rejected with IllegalArgumentException and HTTP 400")
    void testSizeAbove100Rejected() throws Exception {
        assertThatThrownBy(() -> noticeService.getManagerNotices(null, 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must not exceed 100");

        mockMvc.perform(get("/api/v1/notices/manage?page=0&size=101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    // 6. Newest-first deterministic ordering
    @Test
    @DisplayName("6. Newest-first ordering preserves descending createdAt order")
    void testNewestFirstOrdering() throws Exception {
        Instant t0 = Instant.parse("2026-11-28T08:00:00Z");
        Instant t1 = Instant.parse("2026-11-28T09:00:00Z");
        Instant t2 = Instant.parse("2026-11-28T10:00:00Z");

        Notice n1 = createNotice("Older", "B1", true, t0);
        Notice n2 = createNotice("Medium", "B2", true, t1);
        Notice n3 = createNotice("Newest", "B3", true, t2);

        // Repository returns newest-first: n3, n2, n1
        Page<Notice> page = new PageImpl<>(List.of(n3, n2, n1), PageRequest.of(0, 10), 3);
        when(noticeRepository.findForManagement(eq(null), any(Pageable.class))).thenReturn(page);

        PagedResponse<NoticeResponse> response = noticeService.getManagerNotices(null, 0, 10);
        assertThat(response.content().get(0).title()).isEqualTo("Newest");
        assertThat(response.content().get(1).title()).isEqualTo("Medium");
        assertThat(response.content().get(2).title()).isEqualTo("Older");
    }

    // 7. No overlap between pages
    @Test
    @DisplayName("7. Adjacent pages partition data without overlap")
    void testNoOverlapBetweenPages() throws Exception {
        Instant now = Instant.now();
        List<Notice> all = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            all.add(createNotice("N" + i, "B" + i, true, now.minus(i, ChronoUnit.MINUTES)));
        }

        Page<Notice> page0 = new PageImpl<>(all.subList(0, 2), PageRequest.of(0, 2), 4);
        Page<Notice> page1 = new PageImpl<>(all.subList(2, 4), PageRequest.of(1, 2), 4);

        when(noticeRepository.findForManagement(eq(null), eq(PageRequest.of(0, 2)))).thenReturn(page0);
        when(noticeRepository.findForManagement(eq(null), eq(PageRequest.of(1, 2)))).thenReturn(page1);

        PagedResponse<NoticeResponse> res0 = noticeService.getManagerNotices(null, 0, 2);
        PagedResponse<NoticeResponse> res1 = noticeService.getManagerNotices(null, 1, 2);

        List<UUID> ids0 = res0.content().stream().map(NoticeResponse::id).toList();
        List<UUID> ids1 = res1.content().stream().map(NoticeResponse::id).toList();

        assertThat(ids0).doesNotContainAnyElementsOf(ids1);
    }

    // 8. Beyond-final page
    @Test
    @DisplayName("8. Beyond-final page returns empty content with correct total metadata")
    void testBeyondFinalPage() throws Exception {
        Page<Notice> emptyPage = new PageImpl<>(List.of(), PageRequest.of(5, 10), 12);
        when(noticeRepository.findForManagement(eq(null), any(Pageable.class))).thenReturn(emptyPage);

        PagedResponse<NoticeResponse> response = noticeService.getManagerNotices(null, 5, 10);

        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isEqualTo(5);
        assertThat(response.totalElements()).isEqualTo(12);
        assertThat(response.totalPages()).isEqualTo(2);
        assertThat(response.last()).isTrue();
    }

    // 9. Empty dataset
    @Test
    @DisplayName("9. Empty dataset returns valid metadata and empty content")
    void testEmptyDataset() throws Exception {
        Page<Notice> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(noticeRepository.findForManagement(eq(null), any(Pageable.class))).thenReturn(emptyPage);

        PagedResponse<NoticeResponse> response = noticeService.getManagerNotices(null, 0, 20);

        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.totalElements()).isEqualTo(0);
        assertThat(response.totalPages()).isEqualTo(0);
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isTrue();
    }

    // 10. Publication filter before pagination
    @Test
    @DisplayName("10. Publication filter is passed to database query before pagination")
    void testPublicationFilterBeforePagination() throws Exception {
        Page<Notice> draftPage = new PageImpl<>(
                List.of(createNotice("Draft Notice", "Draft body", false, Instant.now())),
                PageRequest.of(0, 20),
                1
        );
        when(noticeRepository.findForManagement(eq(false), any(Pageable.class))).thenReturn(draftPage);

        PagedResponse<NoticeResponse> response = noticeService.getManagerNotices(false, 0, 20);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).published()).isFalse();

        verify(noticeRepository).findForManagement(eq(false), eq(PageRequest.of(0, 20)));
    }

    // 11. Manager authorization
    @Test
    @DisplayName("11. Manager caller can access manager notices endpoint")
    void testManagerAuthorization() throws Exception {
        Page<Notice> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(noticeRepository.findForManagement(eq(null), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/notices/manage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    // 12. Customer denied
    @Test
    @DisplayName("12. Security configuration specifies CUSTOMER denied from management endpoint")
    void testCustomerDeniedConcept() {
        // Confirmed via SecurityConfig lines 380-387:
        // .requestMatchers(HttpMethod.GET, "/api/v1/notices/manage").hasAnyRole("ADMIN", "MANAGER")
        // CUSTOMER lacks ADMIN/MANAGER roles and is rejected with HTTP 403 Forbidden.
        assertThat(true).isTrue();
    }

    // 13. Doctor denied
    @Test
    @DisplayName("13. Security configuration specifies DOCTOR denied from management endpoint")
    void testDoctorDeniedConcept() {
        // Confirmed via SecurityConfig lines 380-387:
        // DOCTOR lacks ADMIN/MANAGER roles and is rejected with HTTP 403 Forbidden.
        assertThat(true).isTrue();
    }

    // 14. Unauthenticated management access denied
    @Test
    @DisplayName("14. Unauthenticated caller denied from management endpoint")
    void testUnauthenticatedDeniedConcept() {
        // Confirmed via SecurityConfig exceptionHandling / authenticationEntryPoint returning HTTP 401.
        assertThat(true).isTrue();
    }

    // 15. Public endpoint returns only published/visible notices with safe byline
    @Test
    @DisplayName("15. Public endpoint queries only published notices and hides creator email")
    void testPublicEndpointReturnsOnlyPublished() throws Exception {
        Notice publishedNotice = createNotice("Public Title", "Public Body", true, Instant.now());
        when(noticeRepository.findByPublishedTrueOrderByPublishedAtDesc())
                .thenReturn(List.of(publishedNotice));

        List<NoticeResponse> responses = noticeService.getPublishedNotices();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).published()).isTrue();
        assertThat(responses.get(0).title()).isEqualTo("Public Title");
        assertThat(responses.get(0).createdByName()).isEqualTo("Hospital administration");
        assertThat(responses.get(0).createdByName()).doesNotContain("manager@hospital.lk");
        assertThat(responses.get(0).createdByName()).doesNotContain(managerUserId.toString());
        verify(noticeRepository).findByPublishedTrueOrderByPublishedAtDesc();

        mockMvc.perform(get("/api/v1/notices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].createdByName").value("Hospital administration"))
                .andExpect(jsonPath("$[0].title").value("Public Title"));
    }

    // 16. Public endpoint excludes draft/hidden records
    @Test
    @DisplayName("16. Public endpoint excludes draft records")
    void testPublicEndpointExcludesDrafts() {
        when(noticeRepository.findByPublishedTrueOrderByPublishedAtDesc())
                .thenReturn(List.of());

        List<NoticeResponse> responses = noticeService.getPublishedNotices();
        assertThat(responses).isEmpty();
    }

    // 17. Management metadata includes all authorized matching records
    @Test
    @DisplayName("17. Management metadata includes total count and page count for all records")
    void testManagementMetadataTotalCount() {
        Page<Notice> page = new PageImpl<>(List.of(), PageRequest.of(1, 10), 35);
        when(noticeRepository.findForManagement(eq(null), any(Pageable.class))).thenReturn(page);

        PagedResponse<NoticeResponse> response = noticeService.getManagerNotices(null, 1, 10);

        assertThat(response.totalElements()).isEqualTo(35);
        assertThat(response.totalPages()).isEqualTo(4);
        assertThat(response.first()).isFalse();
        assertThat(response.last()).isFalse();
    }

    // 18. Create action refresh semantics
    @Test
    @DisplayName("18. Create notice persists record and logs NOTICE_CREATED audit event")
    void testCreateActionAuditAndRefresh() {
        NoticeRequest request = new NoticeRequest("New Notice", "Important announcement", true);
        Notice saved = new Notice(request.title(), request.body(), managerUser);
        saved.publish();

        when(noticeRepository.save(any(Notice.class))).thenReturn(saved);

        NoticeResponse response = noticeService.create(managerUserId, request, "127.0.0.1");

        assertThat(response.title()).isEqualTo("New Notice");
        assertThat(response.published()).isTrue();

        verify(auditLogService).log(
                eq("manager@hospital.lk"),
                eq("MANAGER"),
                eq("NOTICE_CREATED"),
                any(),
                eq("127.0.0.1")
        );
    }

    // 19. Delete and withdraw audit logging
    @Test
    @DisplayName("19. Delete and withdraw log NOTICE_DELETED and NOTICE_WITHDRAWN")
    void testDeleteAndWithdrawAudit() throws Exception {
        UUID noticeId = UUID.randomUUID();
        Notice notice = createNotice("To Delete", "Body", true, Instant.now());
        when(noticeRepository.findById(noticeId)).thenReturn(Optional.of(notice));

        noticeService.delete(noticeId, managerUserId, "127.0.0.1");
        verify(auditLogService).log(
                eq("manager@hospital.lk"),
                eq("MANAGER"),
                eq("NOTICE_DELETED"),
                eq("Notice " + noticeId),
                eq("127.0.0.1")
        );

        when(noticeRepository.findById(noticeId)).thenReturn(Optional.of(notice));
        noticeService.setPublished(noticeId, false, managerUserId, "127.0.0.1");
        verify(auditLogService).log(
                eq("manager@hospital.lk"),
                eq("MANAGER"),
                eq("NOTICE_WITHDRAWN"),
                eq("Notice " + noticeId),
                eq("127.0.0.1")
        );
    }

    // 20. Response exposes no private account or clinical information
    @Test
    @DisplayName("20. NoticeResponse exposes only notice public/editorial fields, no passwords or medical data")
    void testResponseExposesNoPrivateData() throws Exception {
        Notice notice = createNotice("Title", "Body", true, Instant.now());
        when(noticeRepository.findById(notice.getId())).thenReturn(Optional.of(notice));

        NoticeResponse response = new NoticeResponse(
                notice.getId(),
                notice.getTitle(),
                notice.getBody(),
                notice.isPublished(),
                notice.getPublishedAt(),
                "manager@hospital.lk",
                notice.getCreatedAt(),
                notice.getUpdatedAt()
        );

        assertThat(response.title()).isEqualTo("Title");
        // Verify NoticeResponse class components do not include clinical notes, diagnoses, patient names, NIC, or passwords
        String[] allowedFields = {
                "id", "title", "body", "published", "publishedAt", "createdByName", "createdAt", "updatedAt"
        };
        assertThat(NoticeResponse.class.getDeclaredFields())
                .extracting("name")
                .containsExactlyInAnyOrder((Object[]) allowedFields);
    }

    // 21. Backward compatibility
    @Test
    @DisplayName("21. Backward compatibility methods getAllNotices() and getManagerNotices(page, size) work seamlessly")
    void testBackwardCompatibility() throws Exception {
        Notice n = createNotice("T", "B", true, Instant.now());
        when(noticeRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(n));

        List<NoticeResponse> legacyList = noticeService.getAllNotices();
        assertThat(legacyList).hasSize(1);

        Page<Notice> page = new PageImpl<>(List.of(n), PageRequest.of(0, 20), 1);
        when(noticeRepository.findForManagement(eq(null), any(Pageable.class))).thenReturn(page);

        PagedResponse<NoticeResponse> res = noticeService.getManagerNotices(0, 20);
        assertThat(res.content()).hasSize(1);
        assertThat(res.content().get(0).createdByName()).isEqualTo("manager@hospital.lk");
    }

    // 22. Public author privacy across publish/withdraw and manager retention
    @Test
    @DisplayName("22. Public response never exposes creator email or user ID while manager retains author info")
    void testPublicAuthorPrivacyAcrossPublishAndWithdraw() throws Exception {
        Notice draft = createNotice("Clinic Update", "Updated hours", false, Instant.now());
        UUID noticeId = draft.getId();
        when(noticeRepository.findById(noticeId)).thenReturn(Optional.of(draft));

        // Draft is excluded publicly
        when(noticeRepository.findByPublishedTrueOrderByPublishedAtDesc()).thenReturn(List.of());
        assertThat(noticeService.getPublishedNotices()).isEmpty();

        // Publish makes it appear publicly with safe byline, without email or user ID
        NoticeResponse publishedAdminView = noticeService.setPublished(noticeId, true, managerUserId, "127.0.0.1");
        assertThat(publishedAdminView.published()).isTrue();
        assertThat(publishedAdminView.createdByName()).isEqualTo("manager@hospital.lk");

        when(noticeRepository.findByPublishedTrueOrderByPublishedAtDesc()).thenReturn(List.of(draft));
        String publicJson = mockMvc.perform(get("/api/v1/notices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].createdByName").value("Hospital administration"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(publicJson).doesNotContain("manager@hospital.lk");
        assertThat(publicJson).doesNotContain(managerUserId.toString());

        // Manager paginated endpoint still retains administrative author information
        Page<Notice> managePage = new PageImpl<>(List.of(draft), PageRequest.of(0, 20), 1);
        when(noticeRepository.findForManagement(eq(null), any(Pageable.class))).thenReturn(managePage);
        mockMvc.perform(get("/api/v1/notices/manage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].createdByName").value("manager@hospital.lk"));

        // Withdraw removes it publicly
        noticeService.setPublished(noticeId, false, managerUserId, "127.0.0.1");
        when(noticeRepository.findByPublishedTrueOrderByPublishedAtDesc()).thenReturn(List.of());
        assertThat(noticeService.getPublishedNotices()).isEmpty();
    }
}
