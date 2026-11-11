package com.medichannel.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.medichannel.dto.notification.NotificationResponse;
import com.medichannel.dto.notification.UnreadNotificationCountResponse;
import com.medichannel.service.NotificationService;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(
            NotificationService notificationService
    ) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> getMyNotifications(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(
                notificationService.getMyNotifications(userId)
        );
    }

    @GetMapping("/unread-count")
    public ResponseEntity<UnreadNotificationCountResponse> getUnreadCount(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(
                notificationService.getUnreadCount(userId)
        );
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<NotificationResponse> markRead(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID notificationId
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(
                notificationService.markRead(userId, notificationId)
        );
    }

    @PatchMapping("/read-all")
    public ResponseEntity<UnreadNotificationCountResponse> markAllRead(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(
                notificationService.markAllRead(userId)
        );
    }
}
