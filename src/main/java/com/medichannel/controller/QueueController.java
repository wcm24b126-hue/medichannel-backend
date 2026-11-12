package com.medichannel.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medichannel.dto.queue.SessionQueueSummary;
import com.medichannel.service.SessionQueueService;

/**
 * Public, non-identifying queue state for a session. Patients use it
 * to show "now serving" and how many places are left.
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class QueueController {

    private final SessionQueueService sessionQueueService;

    public QueueController(SessionQueueService sessionQueueService) {
        this.sessionQueueService = sessionQueueService;
    }

    @GetMapping("/{sessionId}/queue")
    public ResponseEntity<SessionQueueSummary> getQueue(
            @PathVariable UUID sessionId
    ) {
        return ResponseEntity.ok(
                sessionQueueService.getSummary(sessionId)
        );
    }
}
