package com.medichannel.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.SessionStatus;

import jakarta.persistence.*;

@Entity
@Table(name = "doctor_sessions")
public class DoctorSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "consultation_type", nullable = false, length = 20)
    private ConsultationType consultationType;

    @Column(name = "max_appointments", nullable = false)
    private int maxAppointments;

    @Column(name = "minutes_per_patient", nullable = false)
    private int minutesPerPatient = 10;

    @Column(name = "fee_lkr", nullable = false, precision = 10, scale = 2)
    private BigDecimal feeLkr = BigDecimal.ZERO;

    @Column(name = "delay_minutes", nullable = false)
    private int delayMinutes = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status = SessionStatus.SCHEDULED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DoctorSession() {
    }

    public DoctorSession(
            Doctor doctor,
            Room room,
            LocalDate sessionDate,
            LocalTime startTime,
            LocalTime endTime,
            ConsultationType consultationType,
            int maxAppointments
    ) {
        this.doctor = doctor;
        this.room = room;
        this.sessionDate = sessionDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.consultationType = consultationType;
        this.maxAppointments = maxAppointments;
    }

    public void open() {
        if (status != SessionStatus.SCHEDULED) {
            throw new IllegalStateException("Only scheduled sessions can be opened");
        }
        this.status = SessionStatus.OPEN;
    }

    public void start() {
        if (status != SessionStatus.OPEN) {
            throw new IllegalStateException("Only open sessions can be started");
        }
        this.status = SessionStatus.IN_PROGRESS;
    }

    public void cancel() {
        if (status == SessionStatus.COMPLETED) {
            throw new IllegalStateException("Completed sessions cannot be cancelled");
        }
        this.status = SessionStatus.CANCELLED;
    }

    public void complete() {
        if (status != SessionStatus.IN_PROGRESS) {
            throw new IllegalStateException("Only in-progress sessions can be completed");
        }
        this.status = SessionStatus.COMPLETED;
    }

    public void updateDelay(int delayMinutes) {
        if (delayMinutes < 0) {
            throw new IllegalArgumentException("Delay cannot be negative");
        }
        this.delayMinutes = delayMinutes;
    }

    public void updateSchedulingDetails(
            int minutesPerPatient,
            BigDecimal feeLkr
    ) {
        if (minutesPerPatient < 5 || minutesPerPatient > 30) {
            throw new IllegalArgumentException(
                    "Minutes per patient must be between 5 and 30"
            );
        }

        if (feeLkr == null || feeLkr.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Fee cannot be negative or null");
        }

        this.minutesPerPatient = minutesPerPatient;
        this.feeLkr = feeLkr;
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

    public Doctor getDoctor() {
        return doctor;
    }

    public Room getRoom() {
        return room;
    }

    public LocalDate getSessionDate() {
        return sessionDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public ConsultationType getConsultationType() {
        return consultationType;
    }

    public int getMaxAppointments() {
        return maxAppointments;
    }

    public int getMinutesPerPatient() {
        return minutesPerPatient;
    }

    public BigDecimal getFeeLkr() {
        return feeLkr;
    }

    public int getDelayMinutes() {
        return delayMinutes;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
