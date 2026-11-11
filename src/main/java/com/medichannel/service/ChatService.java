package com.medichannel.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.chat.ChatMessageResponse;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.ChatMessage;
import com.medichannel.entity.User;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.NotificationType;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ForbiddenOperationException;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.ChatMessageRepository;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;

@Service
public class ChatService {

    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_HISTORY_LIMIT = 500;
    private static final int DEFAULT_HISTORY_LIMIT = 200;
    private static final int NOTIFICATION_PREVIEW_LENGTH = 120;

    private final ChatMessageRepository chatMessageRepository;
    private final AppointmentRepository appointmentRepository;
    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final PatientDataProtectionService dataProtectionService;
    private final AppointmentAccessPolicy accessPolicy;
    private final NotificationService notificationService;
    private final Clock clock;

    public ChatService(
            ChatMessageRepository chatMessageRepository,
            AppointmentRepository appointmentRepository,
            UserRepository userRepository,
            PatientRepository patientRepository,
            DoctorRepository doctorRepository,
            PatientDataProtectionService dataProtectionService,
            AppointmentAccessPolicy accessPolicy,
            NotificationService notificationService,
            Clock clock
    ) {
        this.chatMessageRepository = chatMessageRepository;
        this.appointmentRepository = appointmentRepository;
        this.userRepository = userRepository;
        this.patientRepository = patientRepository;
        this.doctorRepository = doctorRepository;
        this.dataProtectionService = dataProtectionService;
        this.accessPolicy = accessPolicy;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    @Transactional
    public ChatMessageResponse sendMessage(
            UUID appointmentId,
            UUID senderUserId,
            String rawContent
    ) {
        Appointment appointment = appointmentRepository
                .findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment was not found."
                ));

        String senderRole = resolveSenderRole(appointment, senderUserId);

        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new ConflictException(
                    "Messages cannot be sent for a cancelled appointment."
            );
        }

        String content = normalizeContent(rawContent);

        User sender = userRepository.findById(senderUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Sender account was not found."
                ));

        ChatMessage message = new ChatMessage(
                appointment,
                sender,
                senderRole,
                content
        );
        message.setSentAt(clock.instant());

        ChatMessage saved = chatMessageRepository.saveAndFlush(message);

        notifyRecipient(appointment, senderUserId, senderRole, content);

        return toResponse(saved, resolveDisplayName(senderUserId, senderRole));
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getHistory(
            UUID appointmentId,
            UUID userId,
            Integer limit
    ) {
        Appointment appointment = appointmentRepository
                .findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment was not found."
                ));

        accessPolicy.requireChatParticipant(appointment, userId);

        int effectiveLimit = normalizeLimit(limit);

        List<ChatMessage> messages = new ArrayList<>(
                chatMessageRepository.findByAppointment_Id(
                        appointmentId,
                        PageRequest.of(
                                0,
                                effectiveLimit,
                                Sort.by(Sort.Direction.DESC, "sentAt")
                        )
                ).getContent()
        );

        Collections.reverse(messages);

        Map<UUID, String> displayNames = new HashMap<>();

        return messages.stream()
                .map(message -> toResponse(
                        message,
                        displayNames.computeIfAbsent(
                                message.getSenderUser().getId(),
                                userIdForKey -> resolveDisplayName(
                                        userIdForKey,
                                        message.getSenderRole()
                                )
                        )
                ))
                .toList();
    }

    private String resolveSenderRole(
            Appointment appointment,
            UUID senderUserId
    ) {
        if (accessPolicy.isPatientOwner(appointment, senderUserId)) {
            return "CUSTOMER";
        }

        if (accessPolicy.isAssignedDoctor(appointment, senderUserId)) {
            return "DOCTOR";
        }

        throw new ForbiddenOperationException(
                "Only the patient and the assigned doctor can send messages in this consultation."
        );
    }

    private String normalizeContent(String rawContent) {
        if (rawContent == null) {
            throw new IllegalArgumentException(
                    "Message content is required."
            );
        }

        String content = rawContent.trim();

        if (content.isEmpty()) {
            throw new IllegalArgumentException(
                    "Message content is required."
            );
        }

        if (content.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException(
                    "Message must not exceed "
                            + MAX_MESSAGE_LENGTH
                            + " characters."
            );
        }

        return content;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_HISTORY_LIMIT;
        }

        if (limit < 1) {
            return DEFAULT_HISTORY_LIMIT;
        }

        return Math.min(limit, MAX_HISTORY_LIMIT);
    }

    private void notifyRecipient(
            Appointment appointment,
            UUID senderUserId,
            String senderRole,
            String content
    ) {
        UUID recipientUserId = "CUSTOMER".equals(senderRole)
                ? appointment.getSession().getDoctor().getUser().getId()
                : appointment.getPatient().getUser().getId();

        String preview = content.length() > NOTIFICATION_PREVIEW_LENGTH
                ? content.substring(0, NOTIFICATION_PREVIEW_LENGTH) + "..."
                : content;

        notificationService.create(
                recipientUserId,
                NotificationType.NEW_CHAT_MESSAGE,
                "New consultation message",
                preview,
                appointment.getId()
        );
    }

    private String resolveDisplayName(UUID userId, String role) {
        if ("CUSTOMER".equals(role)) {
            return patientRepository.findByUserId(userId)
                    .map(patient -> dataProtectionService.decrypt(
                            patient.getFirstNameEncrypted()
                    ) + " " + dataProtectionService.decrypt(
                            patient.getLastNameEncrypted()
                    ))
                    .orElse("Patient");
        }

        return doctorRepository.findByUserId(userId)
                .map(doctor -> doctor.getFirstName()
                        + " " + doctor.getLastName())
                .orElse("Doctor");
    }

    private ChatMessageResponse toResponse(
            ChatMessage message,
            String senderName
    ) {
        return new ChatMessageResponse(
                message.getId(),
                message.getAppointment().getId(),
                message.getSenderUser().getId(),
                message.getSenderRole(),
                senderName,
                message.getContent(),
                message.getSentAt()
        );
    }
}
