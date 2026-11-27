package com.medichannel.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.prepost.PreAuthorize;

import com.medichannel.controller.AuditLogController;
import com.medichannel.dto.audit.AuditLogResponse;
import com.medichannel.dto.common.PagedResponse;
import com.medichannel.entity.AuditLog;
import com.medichannel.repository.AuditLogRepository;
import com.medichannel.repository.UserRepository;

class AuditLogPaginationUnitTests {

    private AuditLogRepository auditLogRepository;
    private UserRepository userRepository;
    private ObjectProvider<SimpMessagingTemplate> messagingTemplateProvider;
    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        userRepository = mock(UserRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SimpMessagingTemplate> provider = mock(ObjectProvider.class);
        messagingTemplateProvider = provider;

        auditLogService = new AuditLogService(
                auditLogRepository,
                messagingTemplateProvider,
                userRepository,
                "127.0.0.1,::1"
        );
    }

    private AuditLog createSampleAuditLog(UUID id, Instant createdAt, UUID userId, String action) {
        AuditLog log = new AuditLog(
                userId,
                "ADMIN",
                action,
                "Admin action performed",
                "192.168.1.1",
                null
        );
        try {
            Field idField = AuditLog.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(log, id);

            Field createdField = AuditLog.class.getDeclaredField("createdAt");
            createdField.setAccessible(true);
            createdField.set(log, createdAt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return log;
    }

    @Test
    @DisplayName("1. Default first page requests page 0 and size 20")
    void defaultFirstPage() {
        Pageable expectedPageable = PageRequest.of(0, 20);
        when(auditLogRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList(), expectedPageable, 0));

        PagedResponse<AuditLogResponse> response = auditLogService.getAuditLogs(0, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogRepository).findAll(captor.capture());

        Pageable captured = captor.getValue();
        assertEquals(0, captured.getPageNumber());
        assertEquals(20, captured.getPageSize());
        assertEquals(0, response.page());
        assertEquals(20, response.size());
        assertTrue(response.first());
        assertTrue(response.last());
    }

    @Test
    @DisplayName("2. Custom page and size parameters are passed through")
    void customPageAndSize() {
        List<AuditLog> logs = List.of(
                createSampleAuditLog(UUID.randomUUID(), Instant.now(), UUID.randomUUID(), "EXPORT_REPORT")
        );
        Pageable pageable = PageRequest.of(1, 15);
        when(auditLogRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(logs, pageable, 45));

        PagedResponse<AuditLogResponse> response = auditLogService.getAuditLogs(1, 15);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogRepository).findAll(captor.capture());

        assertEquals(1, captor.getValue().getPageNumber());
        assertEquals(15, captor.getValue().getPageSize());
        assertEquals(1, response.page());
        assertEquals(15, response.size());
        assertEquals(45, response.totalElements());
        assertEquals(3, response.totalPages());
        assertFalse(response.first());
        assertFalse(response.last());
        assertEquals(1, response.content().size());
    }

    @Test
    @DisplayName("3. Maximum page size enforcement rejects sizes exceeding 100")
    void maximumSizeEnforcement() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> auditLogService.getAuditLogs(0, 101)
        );
        assertTrue(ex.getMessage().contains("must not exceed 100"));
        verify(auditLogRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("4. Invalid negative page is rejected")
    void invalidPageRejected() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> auditLogService.getAuditLogs(-1, 20)
        );
        assertTrue(ex.getMessage().contains("must not be negative"));
        verify(auditLogRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("5. Invalid zero or negative size is rejected")
    void invalidSizeRejected() {
        IllegalArgumentException exZero = assertThrows(
                IllegalArgumentException.class,
                () -> auditLogService.getAuditLogs(0, 0)
        );
        assertTrue(exZero.getMessage().contains("greater than zero"));

        IllegalArgumentException exNegative = assertThrows(
                IllegalArgumentException.class,
                () -> auditLogService.getAuditLogs(0, -10)
        );
        assertTrue(exNegative.getMessage().contains("greater than zero"));

        verify(auditLogRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("6. Newest-first stable ordering by createdAt DESC, id DESC")
    void newestFirstStableOrdering() {
        when(auditLogRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        auditLogService.getAuditLogs(0, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogRepository).findAll(captor.capture());

        Sort sort = captor.getValue().getSort();
        assertNotNull(sort);
        Sort.Order createdAtOrder = sort.getOrderFor("createdAt");
        Sort.Order idOrder = sort.getOrderFor("id");

        assertNotNull(createdAtOrder, "Must include createdAt in sort");
        assertEquals(Sort.Direction.DESC, createdAtOrder.getDirection());

        assertNotNull(idOrder, "Must include id in sort for deterministic tie-breaking");
        assertEquals(Sort.Direction.DESC, idOrder.getDirection());
    }

    @Test
    @DisplayName("7. Second page has no overlap with first page")
    void secondPageHasNoOverlapWithFirst() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        UUID id4 = UUID.randomUUID();

        AuditLog log1 = createSampleAuditLog(id1, Instant.now().minusSeconds(10), UUID.randomUUID(), "ACT_1");
        AuditLog log2 = createSampleAuditLog(id2, Instant.now().minusSeconds(20), UUID.randomUUID(), "ACT_2");
        AuditLog log3 = createSampleAuditLog(id3, Instant.now().minusSeconds(30), UUID.randomUUID(), "ACT_3");
        AuditLog log4 = createSampleAuditLog(id4, Instant.now().minusSeconds(40), UUID.randomUUID(), "ACT_4");

        when(auditLogRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable p = invocation.getArgument(0);
            if (p.getPageNumber() == 0) {
                return new PageImpl<>(List.of(log1, log2), p, 4);
            } else if (p.getPageNumber() == 1) {
                return new PageImpl<>(List.of(log3, log4), p, 4);
            }
            return new PageImpl<>(Collections.emptyList(), p, 4);
        });

        PagedResponse<AuditLogResponse> page1 = auditLogService.getAuditLogs(0, 2);
        PagedResponse<AuditLogResponse> page2 = auditLogService.getAuditLogs(1, 2);

        Set<UUID> page1Ids = new HashSet<>(page1.content().stream().map(AuditLogResponse::id).toList());
        Set<UUID> page2Ids = new HashSet<>(page2.content().stream().map(AuditLogResponse::id).toList());

        assertEquals(2, page1Ids.size());
        assertEquals(2, page2Ids.size());

        Set<UUID> intersection = new HashSet<>(page1Ids);
        intersection.retainAll(page2Ids);
        assertTrue(intersection.isEmpty(), "Page 1 and Page 2 must not have overlapping records");
    }

    @Test
    @DisplayName("8. Empty dataset returns safe metadata with empty content")
    void emptyDataset() {
        when(auditLogRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 20), 0));

        PagedResponse<AuditLogResponse> response = auditLogService.getAuditLogs(0, 20);

        assertNotNull(response);
        assertEquals(0, response.totalElements());
        assertEquals(0, response.totalPages());
        assertEquals(0, response.page());
        assertEquals(20, response.size());
        assertTrue(response.first());
        assertTrue(response.last());
        assertTrue(response.content().isEmpty());
    }

    @Test
    @DisplayName("9. Filter is applied before pagination")
    void filterAppliedBeforePagination() {
        UUID targetUserId = UUID.randomUUID();
        when(auditLogRepository.findByUserId(eq(targetUserId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 20), 0));

        auditLogService.getAuditLogs(0, 20, targetUserId);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogRepository).findByUserId(eq(targetUserId), captor.capture());
        verify(auditLogRepository, never()).findAll(any(Pageable.class));

        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(20, captor.getValue().getPageSize());
    }

    @Test
    @DisplayName("10. ADMIN allowed: AuditLogController endpoint requires ADMIN role")
    void adminAllowed() throws NoSuchMethodException {
        // Verify class or method level PreAuthorize requires ADMIN
        Method getMethod = AuditLogController.class.getMethod("getAuditLogs", int.class, int.class, UUID.class);
        PreAuthorize methodPreAuth = getMethod.getAnnotation(PreAuthorize.class);
        assertNotNull(methodPreAuth, "Method must have @PreAuthorize");
        assertTrue(methodPreAuth.value().contains("hasRole('ADMIN')"),
                "PreAuthorize must mandate ADMIN role");
    }

    @Test
    @DisplayName("11. Non-ADMIN denied: PreAuthorize specifies hasRole('ADMIN') exclusively")
    void nonAdminDenied() throws NoSuchMethodException {
        Method getMethod = AuditLogController.class.getMethod("getAuditLogs", int.class, int.class, UUID.class);
        PreAuthorize methodPreAuth = getMethod.getAnnotation(PreAuthorize.class);
        assertNotNull(methodPreAuth);
        String rule = methodPreAuth.value();

        assertFalse(rule.contains("MANAGER"), "MANAGER must not have access to audit logs");
        assertFalse(rule.contains("DOCTOR"), "DOCTOR must not have access to audit logs");
        assertFalse(rule.contains("CUSTOMER"), "CUSTOMER must not have access to audit logs");
        assertFalse(rule.contains("permitAll"), "Public access must not be allowed");
    }

    @Test
    @DisplayName("12. Response contains no medical, clinical or private health data")
    void responseContainsNoMedicalOrClinicalContent() {
        Set<String> forbiddenTerms = Set.of(
                "diagnosis", "prescription", "treatment", "symptom", "clinical",
                "notes", "medical", "doctornotes", "patientrecord", "condition",
                "medication", "dosage", "allergy", "vitals"
        );

        // Check AuditLogResponse records
        for (RecordComponent rc : AuditLogResponse.class.getRecordComponents()) {
            String name = rc.getName().toLowerCase();
            for (String forbidden : forbiddenTerms) {
                assertFalse(name.contains(forbidden),
                        "AuditLogResponse must not contain medical field: " + name);
            }
        }

        // Check PagedResponse records
        for (RecordComponent rc : PagedResponse.class.getRecordComponents()) {
            String name = rc.getName().toLowerCase();
            for (String forbidden : forbiddenTerms) {
                assertFalse(name.contains(forbidden),
                        "PagedResponse must not contain medical field: " + name);
            }
        }
    }
}
