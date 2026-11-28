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
import com.medichannel.dto.notice.NoticeRequest;
import com.medichannel.dto.notice.NoticeResponse;
import com.medichannel.service.NoticeService;

@RestController
@RequestMapping("/api/v1/notices")
public class NoticeController {

    private final NoticeService noticeService;

    public NoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    // Public: patients and guests read published hospital notices.
    @GetMapping
    public ResponseEntity<List<NoticeResponse>> getPublishedNotices() {
        return ResponseEntity.ok(noticeService.getPublishedNotices());
    }

    // Managers and administrators see drafts as well, with server-side pagination.
    @GetMapping("/manage")
    public ResponseEntity<PagedResponse<NoticeResponse>> getAllNotices(
            @RequestParam(required = false) Boolean published,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(noticeService.getManagerNotices(published, page, size));
    }

    @PostMapping
    public ResponseEntity<NoticeResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody NoticeRequest request,
            HttpServletRequest httpRequest
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(noticeService.create(
                        userId,
                        request,
                        clientIp(httpRequest)
                ));
    }

    @PutMapping("/{noticeId}")
    public ResponseEntity<NoticeResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID noticeId,
            @Valid @RequestBody NoticeRequest request,
            HttpServletRequest httpRequest
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(noticeService.update(
                noticeId,
                request,
                userId,
                clientIp(httpRequest)
        ));
    }

    @PatchMapping("/{noticeId}/publish")
    public ResponseEntity<NoticeResponse> publish(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID noticeId,
            HttpServletRequest httpRequest
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(noticeService.setPublished(
                noticeId,
                true,
                userId,
                clientIp(httpRequest)
        ));
    }

    @PatchMapping("/{noticeId}/withdraw")
    public ResponseEntity<NoticeResponse> withdraw(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID noticeId,
            HttpServletRequest httpRequest
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(noticeService.setPublished(
                noticeId,
                false,
                userId,
                clientIp(httpRequest)
        ));
    }

    @DeleteMapping("/{noticeId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID noticeId,
            HttpServletRequest httpRequest
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        noticeService.delete(noticeId, userId, clientIp(httpRequest));
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
