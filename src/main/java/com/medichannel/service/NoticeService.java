package com.medichannel.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.common.PagedResponse;
import com.medichannel.dto.notice.NoticeRequest;
import com.medichannel.dto.notice.NoticeResponse;
import com.medichannel.entity.Notice;
import com.medichannel.entity.User;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.NoticeRepository;
import com.medichannel.repository.UserRepository;

@Service
public class NoticeService {

    private static final String PUBLIC_AUTHOR_BYLINE = "Hospital administration";

    private final NoticeRepository noticeRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public NoticeService(
            NoticeRepository noticeRepository,
            UserRepository userRepository,
            AuditLogService auditLogService
    ) {
        this.noticeRepository = noticeRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<NoticeResponse> getPublishedNotices() {
        return noticeRepository.findByPublishedTrueOrderByPublishedAtDesc()
                .stream()
                .map(this::toPublicResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<NoticeResponse> getAllNotices() {
        return noticeRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toManagementResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PagedResponse<NoticeResponse> getManagerNotices(
            Boolean published,
            int page,
            int size
    ) {
        validatePagination(page, size);
        Pageable pageable = PageRequest.of(page, size);
        Page<Notice> noticePage = noticeRepository.findForManagement(published, pageable);
        return PagedResponse.from(noticePage.map(this::toManagementResponse));
    }

    @Transactional(readOnly = true)
    public PagedResponse<NoticeResponse> getManagerNotices(int page, int size) {
        return getManagerNotices(null, page, size);
    }

    private void validatePagination(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("Page index must not be negative.");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than zero.");
        }
        if (size > 100) {
            throw new IllegalArgumentException("Page size must not exceed 100.");
        }
    }

    @Transactional
    public NoticeResponse create(
            UUID actorUserId,
            NoticeRequest request,
            String ipAddress
    ) {
        User actor = requireUser(actorUserId);

        Notice notice = new Notice(
                request.title().trim(),
                request.body().trim(),
                actor
        );
        if (request.published()) {
            notice.publish();
        }

        Notice saved = noticeRepository.save(notice);
        auditLogService.log(
                actor.getEmail(),
                actor.getRole().getName(),
                "NOTICE_CREATED",
                "Notice " + saved.getId() + " created (published="
                        + saved.isPublished() + ")",
                ipAddress
        );
        return toResponse(saved);
    }

    @Transactional
    public NoticeResponse update(
            UUID noticeId,
            NoticeRequest request,
            UUID actorUserId,
            String ipAddress
    ) {
        Notice notice = requireNotice(noticeId);
        User actor = requireUser(actorUserId);

        notice.update(request.title().trim(), request.body().trim());
        if (request.published()) {
            notice.publish();
        } else {
            notice.withdraw();
        }

        auditLogService.log(
                actor.getEmail(),
                actor.getRole().getName(),
                "NOTICE_UPDATED",
                "Notice " + noticeId + " updated (published="
                        + notice.isPublished() + ")",
                ipAddress
        );
        return toResponse(notice);
    }

    @Transactional
    public NoticeResponse setPublished(
            UUID noticeId,
            boolean published,
            UUID actorUserId,
            String ipAddress
    ) {
        Notice notice = requireNotice(noticeId);
        User actor = requireUser(actorUserId);

        if (published) {
            notice.publish();
        } else {
            notice.withdraw();
        }

        auditLogService.log(
                actor.getEmail(),
                actor.getRole().getName(),
                published ? "NOTICE_PUBLISHED" : "NOTICE_WITHDRAWN",
                "Notice " + noticeId,
                ipAddress
        );
        return toResponse(notice);
    }

    @Transactional
    public void delete(
            UUID noticeId,
            UUID actorUserId,
            String ipAddress
    ) {
        Notice notice = requireNotice(noticeId);
        User actor = requireUser(actorUserId);

        noticeRepository.delete(notice);
        auditLogService.log(
                actor.getEmail(),
                actor.getRole().getName(),
                "NOTICE_DELETED",
                "Notice " + noticeId,
                ipAddress
        );
    }

    private Notice requireNotice(UUID noticeId) {
        return noticeRepository.findById(noticeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notice was not found."
                ));
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User was not found."
                ));
    }

    private NoticeResponse toPublicResponse(Notice notice) {
        return toResponse(notice, PUBLIC_AUTHOR_BYLINE);
    }

    private NoticeResponse toManagementResponse(Notice notice) {
        String createdByName = notice.getCreatedBy() == null
                ? null
                : notice.getCreatedBy().getEmail();
        return toResponse(notice, createdByName);
    }

    private NoticeResponse toResponse(Notice notice, String createdByName) {
        return new NoticeResponse(
                notice.getId(),
                notice.getTitle(),
                notice.getBody(),
                notice.isPublished(),
                notice.getPublishedAt(),
                createdByName,
                notice.getCreatedAt(),
                notice.getUpdatedAt()
        );
    }

    // Legacy 1-arg helper retained for management mutations.
    private NoticeResponse toResponse(Notice notice) {
        return toManagementResponse(notice);
    }
}
