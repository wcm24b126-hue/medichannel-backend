package com.medichannel.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.consultation.ConsultationSummaryRequest;
import com.medichannel.dto.consultation.ConsultationSummaryResponse;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.ConsultationSummary;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.NotificationType;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ForbiddenOperationException;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.ConsultationSummaryRepository;
import com.medichannel.security.PatientDataProtectionService;

@Service
public class ConsultationSummaryService {

    private final ConsultationSummaryRepository summaryRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentAccessPolicy accessPolicy;
    private final PatientDataProtectionService dataProtectionService;
    private final NotificationService notificationService;

    public ConsultationSummaryService(
            ConsultationSummaryRepository summaryRepository,
            AppointmentRepository appointmentRepository,
            AppointmentAccessPolicy accessPolicy,
            PatientDataProtectionService dataProtectionService,
            NotificationService notificationService
    ) {
        this.summaryRepository = summaryRepository;
        this.appointmentRepository = appointmentRepository;
        this.accessPolicy = accessPolicy;
        this.dataProtectionService = dataProtectionService;
        this.notificationService = notificationService;
    }

    @Transactional
    public ConsultationSummaryResponse create(
            UUID doctorUserId,
            UUID appointmentId,
            ConsultationSummaryRequest request
    ) {
        Appointment appointment = findAppointment(appointmentId);

        accessPolicy.requireAssignedDoctor(appointment, doctorUserId);

        if (appointment.getStatus() != AppointmentStatus.COMPLETED) {
            throw new ConflictException(
                    "A consultation summary can only be created after the consultation is completed."
            );
        }

        if (summaryRepository.existsByAppointment_Id(appointmentId)) {
            throw new ConflictException(
                    "A consultation summary already exists for this appointment."
            );
        }

        validateRequest(request);

        ConsultationSummary summary = new ConsultationSummary(
                appointment,
                appointment.getSession().getDoctor(),
                appointment.getPatient(),
                appointment.getSession().getSessionDate(),
                encryptOptional(request.symptoms()),
                dataProtectionService.encrypt(
                        request.diagnosis().trim()
                ),
                encryptOptional(request.treatmentNotes()),
                encryptOptional(request.prescription()),
                encryptOptional(request.followUpInstructions()),
                request.followUpDate()
        );

        ConsultationSummary saved = summaryRepository.saveAndFlush(
                summary
        );

        notificationService.create(
                appointment.getPatient().getUser().getId(),
                NotificationType.SUMMARY_AVAILABLE,
                "Consultation summary available",
                "Your consultation summary is now available to view.",
                appointment.getId()
        );

        return toResponse(saved);
    }

    @Transactional
    public ConsultationSummaryResponse update(
            UUID doctorUserId,
            UUID appointmentId,
            ConsultationSummaryRequest request
    ) {
        Appointment appointment = findAppointment(appointmentId);

        accessPolicy.requireAssignedDoctor(appointment, doctorUserId);

        ConsultationSummary summary = summaryRepository
                .findByAppointment_Id(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Consultation summary was not found."
                ));

        validateRequest(request);

        summary.updateClinicalDetails(
                encryptOptional(request.symptoms()),
                dataProtectionService.encrypt(
                        request.diagnosis().trim()
                ),
                encryptOptional(request.treatmentNotes()),
                encryptOptional(request.prescription()),
                encryptOptional(request.followUpInstructions()),
                request.followUpDate()
        );

        return toResponse(summary);
    }

    @Transactional(readOnly = true)
    public ConsultationSummaryResponse get(
            UUID actorUserId,
            String actorRole,
            UUID appointmentId
    ) {
        Appointment appointment = findAppointment(appointmentId);

        boolean patientOwner = accessPolicy.isPatientOwner(
                appointment,
                actorUserId
        );

        boolean allowed = patientOwner
                || accessPolicy.isAssignedDoctor(appointment, actorUserId);

        if (!allowed) {
            throw new ForbiddenOperationException(
                    "You are not authorized to view this consultation summary."
            );
        }

        ConsultationSummary summary = summaryRepository
                .findByAppointment_Id(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Consultation summary was not found."
                ));

        if (patientOwner
                && appointment.getStatus() != AppointmentStatus.COMPLETED) {
            throw new ForbiddenOperationException(
                    "The consultation summary is only available after the consultation is completed."
            );
        }

        return toResponse(summary);
    }

    private Appointment findAppointment(UUID appointmentId) {
        return appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment was not found."
                ));
    }

    private void validateRequest(ConsultationSummaryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Consultation summary information is required."
            );
        }

        if (request.diagnosis() == null
                || request.diagnosis().isBlank()) {
            throw new IllegalArgumentException(
                    "Diagnosis is required."
            );
        }
    }

    private String encryptOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return dataProtectionService.encrypt(value.trim());
    }

    private String decryptOptional(String value) {
        return dataProtectionService.decrypt(value);
    }

    private ConsultationSummaryResponse toResponse(
            ConsultationSummary summary
    ) {
        return new ConsultationSummaryResponse(
                summary.getId(),
                summary.getAppointment().getId(),
                summary.getDoctor().getId(),
                summary.getDoctor().getFirstName()
                        + " " + summary.getDoctor().getLastName(),
                summary.getPatient().getId(),
                summary.getConsultationDate(),
                decryptOptional(summary.getSymptomsEncrypted()),
                decryptOptional(summary.getDiagnosisEncrypted()),
                decryptOptional(summary.getTreatmentNotesEncrypted()),
                decryptOptional(summary.getPrescriptionEncrypted()),
                decryptOptional(summary.getFollowUpInstructionsEncrypted()),
                summary.getFollowUpDate(),
                summary.getCreatedAt(),
                summary.getUpdatedAt()
        );
    }
}
