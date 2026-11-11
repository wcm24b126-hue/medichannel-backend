package com.medichannel.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.medichannel.dto.notification.NotificationResponse;
import com.medichannel.dto.notification.UnreadNotificationCountResponse;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.Notification;
import com.medichannel.entity.User;
import com.medichannel.enums.NotificationType;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.NotificationRepository;
import com.medichannel.repository.UserRepository;

@Service
public class NotificationService {

    private static final String USER_DESTINATION = "/queue/notifications";

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final AppointmentRepository appointmentRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    public NotificationService(
            NotificationRepository notificationRepository,
            UserRepository userRepository,
            AppointmentRepository appointmentRepository,
            SimpMessagingTemplate messagingTemplate,
            Clock clock
    ) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.appointmentRepository = appointmentRepository;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    /**
     * Persist a notification and push it to the recipient's private
     * STOMP queue after the surrounding transaction commits.
     */
    @Transactional
    public NotificationResponse create(
            UUID recipientUserId,
            NotificationType type,
            String title,
            String message,
            UUID appointmentId
    ) {
        User recipient = userRepository.findById(recipientUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification recipient was not found."
                ));

        Appointment appointment = appointmentId == null
                ? null
                : appointmentRepository.findById(appointmentId)
                        .orElse(null);

        Notification notification = new Notification(
                recipient,
                appointment,
                type,
                title,
                message
        );

        Notification saved = notificationRepository.saveAndFlush(
                notification
        );

        NotificationResponse response = toResponse(saved);
        pushAfterCommit(recipientUserId, response);

        return response;
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getMyNotifications(UUID userId) {
        return notificationRepository
                .findByUser_IdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UnreadNotificationCountResponse getUnreadCount(UUID userId) {
        return new UnreadNotificationCountResponse(
                notificationRepository.countByUser_IdAndReadFalse(userId)
        );
    }

    @Transactional
    public NotificationResponse markRead(
            UUID userId,
            UUID notificationId
    ) {
        Notification notification = notificationRepository
                .findByIdAndUser_Id(notificationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification was not found."
                ));

        notification.markRead(clock.instant());

        return toResponse(notification);
    }

    @Transactional
    public UnreadNotificationCountResponse markAllRead(UUID userId) {
        notificationRepository.markAllRead(userId, clock.instant());
        return new UnreadNotificationCountResponse(0);
    }

    private void pushAfterCommit(
            UUID userId,
            NotificationResponse response
    ) {
        Runnable push = () -> messagingTemplate.convertAndSendToUser(
                userId.toString(),
                USER_DESTINATION,
                response
        );

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            push.run();
                        }
                    }
            );
            return;
        }

        push.run();
    }

    private NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getAppointment() == null
                        ? null
                        : notification.getAppointment().getId(),
                notification.isRead(),
                notification.getReadAt(),
                notification.getCreatedAt()
        );
    }
}
