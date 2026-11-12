package com.medichannel.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.medichannel.dto.queue.SessionQueueSummary;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.DoctorSession;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.DoctorSessionRepository;

/**
 * Builds the live queue state for a session and broadcasts it to
 * connected clients after the surrounding transaction commits.
 *
 * The queue pointer is derived from appointment numbers allocated by
 * the database. Numbers are never reassigned.
 */
@Service
public class SessionQueueService {

    public static final String SESSION_TOPIC_PREFIX = "/topic/sessions/";
    public static final String SESSION_TOPIC_SUFFIX = "/queue";
    public static final String CATALOGUE_TOPIC = "/topic/sessions";

    private final AppointmentRepository appointmentRepository;
    private final DoctorSessionRepository doctorSessionRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationService notificationService;
    private final Clock clock;

    public SessionQueueService(
            AppointmentRepository appointmentRepository,
            DoctorSessionRepository doctorSessionRepository,
            SimpMessagingTemplate messagingTemplate,
            NotificationService notificationService,
            Clock clock
    ) {
        this.appointmentRepository = appointmentRepository;
        this.doctorSessionRepository = doctorSessionRepository;
        this.messagingTemplate = messagingTemplate;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SessionQueueSummary getSummary(UUID sessionId) {
        DoctorSession session = doctorSessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Doctor session was not found."
                ));

        return buildSummary(session);
    }

    /**
     * Compute the queue for an already loaded session. Must be called
     * from inside the transaction that changed the appointment state.
     */
    public SessionQueueSummary buildSummary(DoctorSession session) {
        UUID sessionId = session.getId();

        Integer nowServing = appointmentRepository
                .findFirstBySession_IdAndStatusOrderByAppointmentNumberAsc(
                        sessionId,
                        AppointmentStatus.IN_CONSULTATION
                )
                .map(Appointment::getAppointmentNumber)
                .orElse(null);

        Integer nextNumber = appointmentRepository
                .findFirstBySession_IdAndStatusOrderByAppointmentNumberAsc(
                        sessionId,
                        AppointmentStatus.ARRIVED
                )
                .map(Appointment::getAppointmentNumber)
                .orElseGet(() -> appointmentRepository
                        .findFirstBySession_IdAndStatusOrderByAppointmentNumberAsc(
                                sessionId,
                                AppointmentStatus.BOOKED
                        )
                        .map(Appointment::getAppointmentNumber)
                        .orElse(null)
                );

        int remaining = Math.max(
                0,
                session.getMaxAppointments()
                        - (int) appointmentRepository.countBySession_Id(
                                sessionId
                        )
        );

        return new SessionQueueSummary(
                sessionId,
                nowServing,
                nextNumber,
                remaining,
                session.getMaxAppointments(),
                session.getDelayMinutes(),
                session.getStatus(),
                clock.instant()
        );
    }

    /**
     * Publish the queue to everyone watching this session, plus the
     * catalogue topic so search results can refresh capacity.
     */
    public void broadcast(SessionQueueSummary summary) {
        Runnable publish = () -> {
            messagingTemplate.convertAndSend(
                    SESSION_TOPIC_PREFIX + summary.sessionId()
                            + SESSION_TOPIC_SUFFIX,
                    summary
            );
            messagingTemplate.convertAndSend(CATALOGUE_TOPIC, summary);
            notifyPatientThreeNumbersAway(summary);
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publish.run();
                        }
                    }
            );
            return;
        }

        publish.run();
    }

    private void notifyPatientThreeNumbersAway(SessionQueueSummary summary) {
        if (summary.nowServing() == null
                || summary.status() == com.medichannel.enums.SessionStatus.CANCELLED
                || summary.status() == com.medichannel.enums.SessionStatus.COMPLETED) {
            return;
        }
        appointmentRepository.findBySession_IdAndAppointmentNumber(
                summary.sessionId(), summary.nowServing() + 3
        ).filter(appointment ->
                appointment.getStatus() == AppointmentStatus.BOOKED
                        || appointment.getStatus() == AppointmentStatus.ARRIVED
        ).ifPresent(appointment -> notificationService.createIfAbsent(
                appointment.getPatient().getUser().getId(),
                com.medichannel.enums.NotificationType.THREE_NUMBERS_AWAY,
                "You are three numbers away",
                "Your appointment is three numbers away from being called.",
                appointment.getId()
        ));
    }

    public void broadcastAppointmentStatus(
            UUID appointmentId,
            AppointmentStatus status,
            Instant changedAt
    ) {
        Runnable publish = () -> messagingTemplate.convertAndSend(
                "/topic/appointments/" + appointmentId + "/status",
                new AppointmentStatusEvent(appointmentId, status, changedAt)
        );

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publish.run();
                        }
                    }
            );
            return;
        }

        publish.run();
    }

    public record AppointmentStatusEvent(
            UUID appointmentId,
            AppointmentStatus status,
            Instant changedAt
    ) {
    }
}
