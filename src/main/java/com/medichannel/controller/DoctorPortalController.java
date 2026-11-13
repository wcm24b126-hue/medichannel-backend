package com.medichannel.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.medichannel.dto.doctor.DoctorAppointmentResponse;
import com.medichannel.dto.doctor.DoctorResponse;
import com.medichannel.dto.queue.CompleteAndAdvanceResponse;
import com.medichannel.dto.queue.SessionQueueResponse;
import com.medichannel.dto.session.DoctorSessionResponse;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.service.AppointmentService;
import com.medichannel.service.DoctorService;
import com.medichannel.service.DoctorSessionService;

@RestController
@RequestMapping("/api/v1/doctor")
public class DoctorPortalController {

    private final DoctorService doctorService;
    private final AppointmentService appointmentService;
    private final DoctorSessionService sessionService;

    public DoctorPortalController(
            DoctorService doctorService,
            AppointmentService appointmentService,
            DoctorSessionService sessionService
    ) {
        this.doctorService = doctorService;
        this.appointmentService = appointmentService;
        this.sessionService = sessionService;
    }

    @GetMapping("/profile")
    public ResponseEntity<DoctorResponse> getProfile(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(
                doctorService.getProfileForUser(userId(jwt))
        );
    }

    @GetMapping("/appointments")
    public ResponseEntity<List<DoctorAppointmentResponse>> getAppointments(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) AppointmentStatus status
    ) {
        return ResponseEntity.ok(
                appointmentService.getDoctorAppointments(
                        userId(jwt),
                        date,
                        status
                )
        );
    }

    @GetMapping("/appointments/{appointmentId}")
    public ResponseEntity<DoctorAppointmentResponse> getAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId
    ) {
        return ResponseEntity.ok(
                appointmentService.getDoctorAppointment(
                        userId(jwt),
                        appointmentId
                )
        );
    }

    @PatchMapping("/appointments/{appointmentId}/accept")
    public ResponseEntity<DoctorAppointmentResponse> acceptAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId
    ) {
        return ResponseEntity.ok(
                appointmentService.acceptAppointmentByDoctor(
                        userId(jwt),
                        appointmentId
                )
        );
    }

    @PatchMapping("/appointments/{appointmentId}/decline")
    public ResponseEntity<DoctorAppointmentResponse> declineAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId
    ) {
        return ResponseEntity.ok(
                appointmentService.declineAppointmentByDoctor(
                        userId(jwt),
                        appointmentId
                )
        );
    }

    @PatchMapping("/appointments/{appointmentId}/arrive")
    public ResponseEntity<DoctorAppointmentResponse> markArrived(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId
    ) {
        return ResponseEntity.ok(
                appointmentService.markArrived(
                        userId(jwt),
                        appointmentId
                )
        );
    }

    @PatchMapping("/appointments/{appointmentId}/start")
    public ResponseEntity<DoctorAppointmentResponse> startConsultation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId
    ) {
        return ResponseEntity.ok(
                appointmentService.startConsultation(
                        userId(jwt),
                        appointmentId
                )
        );
    }

    @PatchMapping("/appointments/{appointmentId}/complete")
    public ResponseEntity<DoctorAppointmentResponse> completeConsultation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId
    ) {
        return ResponseEntity.ok(
                appointmentService.completeConsultation(
                        userId(jwt),
                        appointmentId
                )
        );
    }

    @PatchMapping("/appointments/{appointmentId}/no-show")
    public ResponseEntity<DoctorAppointmentResponse> markNoShow(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId
    ) {
        return ResponseEntity.ok(
                appointmentService.markNoShow(
                        userId(jwt),
                        appointmentId
                )
        );
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<DoctorSessionResponse>> getSessions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) LocalDate date
    ) {
        return ResponseEntity.ok(
                sessionService.getDoctorSessions(userId(jwt), date)
        );
    }

    @GetMapping("/sessions/{sessionId}/queue")
    public ResponseEntity<SessionQueueResponse> getSessionQueue(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID sessionId
    ) {
        return ResponseEntity.ok(
                appointmentService.getDoctorSessionQueue(
                        userId(jwt),
                        sessionId
                )
        );
    }

    @PatchMapping("/appointments/{appointmentId}/complete-and-advance")
    public ResponseEntity<CompleteAndAdvanceResponse> completeAndAdvance(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId
    ) {
        return ResponseEntity.ok(
                appointmentService.completeAndAdvance(
                        userId(jwt),
                        appointmentId
                )
        );
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
