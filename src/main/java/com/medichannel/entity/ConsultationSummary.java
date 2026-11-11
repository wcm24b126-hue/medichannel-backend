package com.medichannel.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "consultation_summaries")
public class ConsultationSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "appointment_id",
        nullable = false,
        unique = true,
        foreignKey = @ForeignKey(name = "fk_summaries_appointment")
    )
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "doctor_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_summaries_doctor")
    )
    private Doctor doctor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "patient_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_summaries_patient")
    )
    private Patient patient;

    @Column(name = "consultation_date", nullable = false)
    private LocalDate consultationDate;

    @Column(name = "symptoms_encrypted", columnDefinition = "TEXT")
    private String symptomsEncrypted;

    @Column(name = "diagnosis_encrypted", nullable = false, columnDefinition = "TEXT")
    private String diagnosisEncrypted;

    @Column(name = "treatment_notes_encrypted", columnDefinition = "TEXT")
    private String treatmentNotesEncrypted;

    @Column(name = "prescription_encrypted", columnDefinition = "TEXT")
    private String prescriptionEncrypted;

    @Column(name = "follow_up_instructions_encrypted", columnDefinition = "TEXT")
    private String followUpInstructionsEncrypted;

    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConsultationSummary() {
    }

    public ConsultationSummary(
            Appointment appointment,
            Doctor doctor,
            Patient patient,
            LocalDate consultationDate,
            String symptomsEncrypted,
            String diagnosisEncrypted,
            String treatmentNotesEncrypted,
            String prescriptionEncrypted,
            String followUpInstructionsEncrypted,
            LocalDate followUpDate
    ) {
        this.appointment = appointment;
        this.doctor = doctor;
        this.patient = patient;
        this.consultationDate = consultationDate;
        updateClinicalDetails(
                symptomsEncrypted,
                diagnosisEncrypted,
                treatmentNotesEncrypted,
                prescriptionEncrypted,
                followUpInstructionsEncrypted,
                followUpDate
        );
    }

    public void updateClinicalDetails(
            String symptomsEncrypted,
            String diagnosisEncrypted,
            String treatmentNotesEncrypted,
            String prescriptionEncrypted,
            String followUpInstructionsEncrypted,
            LocalDate followUpDate
    ) {
        if (diagnosisEncrypted == null || diagnosisEncrypted.isBlank()) {
            throw new IllegalArgumentException(
                    "Diagnosis is required."
            );
        }

        if (followUpDate != null
                && consultationDate != null
                && followUpDate.isBefore(consultationDate)) {
            throw new IllegalArgumentException(
                    "Follow-up date cannot be before the consultation date."
            );
        }

        this.symptomsEncrypted = symptomsEncrypted;
        this.diagnosisEncrypted = diagnosisEncrypted;
        this.treatmentNotesEncrypted = treatmentNotesEncrypted;
        this.prescriptionEncrypted = prescriptionEncrypted;
        this.followUpInstructionsEncrypted = followUpInstructionsEncrypted;
        this.followUpDate = followUpDate;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Appointment getAppointment() {
        return appointment;
    }

    public Doctor getDoctor() {
        return doctor;
    }

    public Patient getPatient() {
        return patient;
    }

    public LocalDate getConsultationDate() {
        return consultationDate;
    }

    public String getSymptomsEncrypted() {
        return symptomsEncrypted;
    }

    public String getDiagnosisEncrypted() {
        return diagnosisEncrypted;
    }

    public String getTreatmentNotesEncrypted() {
        return treatmentNotesEncrypted;
    }

    public String getPrescriptionEncrypted() {
        return prescriptionEncrypted;
    }

    public String getFollowUpInstructionsEncrypted() {
        return followUpInstructionsEncrypted;
    }

    public LocalDate getFollowUpDate() {
        return followUpDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
