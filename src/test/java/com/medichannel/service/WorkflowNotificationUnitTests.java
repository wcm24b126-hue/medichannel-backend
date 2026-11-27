package com.medichannel.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.medichannel.dto.appointment.AppointmentBookingRequest;
import com.medichannel.dto.notification.NotificationResponse;
import com.medichannel.dto.payment.RecordPaymentRequest;
import com.medichannel.dto.queue.SessionQueueSummary;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Notification;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Payment;
import com.medichannel.entity.Role;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.ConsultationPeriod;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.NotificationType;
import com.medichannel.enums.PaymentMethod;
import com.medichannel.enums.PaymentStatus;
import com.medichannel.enums.PaymentTransactionStatus;
import com.medichannel.enums.SessionStatus;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.NotificationRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.PaymentRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;

class WorkflowNotificationUnitTests {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-28T02:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private NotificationRepository notificationRepository;
    private UserRepository userRepository;
    private AppointmentRepository appointmentRepository;
    private DoctorSessionRepository doctorSessionRepository;
    private PatientRepository patientRepository;
    private PaymentRepository paymentRepository;
    private RoleRepository roleRepository;
    private PasswordEncoder passwordEncoder;
    private PatientDataProtectionService dataProtectionService;
    private SimpMessagingTemplate messagingTemplate;
    private SessionQueueService sessionQueueService;
    private AuditLogService auditLogService;
    private AppointmentAccessPolicy accessPolicy;

    private NotificationService notificationService;
    private AppointmentService appointmentService;
    private PaymentService paymentService;

    private UUID patientUserId;
    private UUID doctorUserId;
    private UUID appointmentId;
    private UUID sessionId;
    private User patientUser;
    private User doctorUser;
    private Patient patient;
    private Doctor doctor;
    private DoctorSession session;
    private Appointment appointment;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        userRepository = mock(UserRepository.class);
        appointmentRepository = mock(AppointmentRepository.class);
        doctorSessionRepository = mock(DoctorSessionRepository.class);
        patientRepository = mock(PatientRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        roleRepository = mock(RoleRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        dataProtectionService = mock(PatientDataProtectionService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        sessionQueueService = mock(SessionQueueService.class);
        auditLogService = mock(AuditLogService.class);
        accessPolicy = new AppointmentAccessPolicy();

        notificationService = new NotificationService(
                notificationRepository,
                userRepository,
                appointmentRepository,
                messagingTemplate,
                FIXED_CLOCK
        );

        appointmentService = new AppointmentService(
                appointmentRepository,
                doctorSessionRepository,
                patientRepository,
                userRepository,
                notificationRepository,
                dataProtectionService,
                roleRepository,
                passwordEncoder,
                notificationService,
                accessPolicy,
                sessionQueueService,
                auditLogService,
                FIXED_CLOCK
        );

        paymentService = new PaymentService(
                paymentRepository,
                appointmentRepository,
                userRepository,
                accessPolicy,
                notificationService,
                sessionQueueService,
                FIXED_CLOCK
        );

        patientUserId = UUID.randomUUID();
        doctorUserId = UUID.randomUUID();
        appointmentId = UUID.randomUUID();
        sessionId = UUID.randomUUID();

        Role customerRole = mock(Role.class);
        when(customerRole.getName()).thenReturn("CUSTOMER");

        patientUser = mock(User.class);
        when(patientUser.getId()).thenReturn(patientUserId);
        when(patientUser.getRole()).thenReturn(customerRole);
        when(patientUser.getAccountStatus()).thenReturn("ACTIVE");
        when(patientUser.isEmailVerified()).thenReturn(true);

        doctorUser = mock(User.class);
        when(doctorUser.getId()).thenReturn(doctorUserId);

        patient = mock(Patient.class);
        when(patient.getId()).thenReturn(UUID.randomUUID());
        when(patient.getUser()).thenReturn(patientUser);
        when(patient.getFirstNameEncrypted()).thenReturn("enc-first");
        when(patient.getLastNameEncrypted()).thenReturn("enc-last");

        Specialisation spec = mock(Specialisation.class);
        when(spec.getName()).thenReturn("Cardiology");

        doctor = mock(Doctor.class);
        when(doctor.getId()).thenReturn(UUID.randomUUID());
        when(doctor.getUser()).thenReturn(doctorUser);
        when(doctor.getFirstName()).thenReturn("Nimal");
        when(doctor.getLastName()).thenReturn("Perera");
        when(doctor.getSpecialisation()).thenReturn(spec);
        when(doctor.isAvailable()).thenReturn(true);

        session = mock(DoctorSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getDoctor()).thenReturn(doctor);
        when(session.getSessionDate()).thenReturn(LocalDate.of(2026, 11, 29));
        when(session.getStartTime()).thenReturn(LocalTime.of(7, 0));
        when(session.getEndTime()).thenReturn(LocalTime.of(11, 0));
        when(session.getMinutesPerPatient()).thenReturn(5);
        when(session.getMaxAppointments()).thenReturn(20);
        when(session.getConsultationType()).thenReturn(ConsultationType.ONLINE);
        when(session.getStatus()).thenReturn(SessionStatus.OPEN);
        when(session.getFeeLkr()).thenReturn(new BigDecimal("2500.00"));

        appointment = mock(Appointment.class);
        when(appointment.getId()).thenReturn(appointmentId);
        when(appointment.getPatient()).thenReturn(patient);
        when(appointment.getSession()).thenReturn(session);
        when(appointment.getAppointmentNumber()).thenReturn(1);
        when(appointment.getStatus()).thenReturn(AppointmentStatus.BOOKED);
        when(appointment.getAcceptanceStatus()).thenReturn("PENDING");
        when(appointment.getPaymentStatus()).thenReturn(PaymentStatus.PENDING);

        when(userRepository.findById(patientUserId)).thenReturn(Optional.of(patientUser));
        when(userRepository.findById(doctorUserId)).thenReturn(Optional.of(doctorUser));
        when(userRepository.findByIdWithRole(patientUserId)).thenReturn(Optional.of(patientUser));
        when(patientRepository.findByUserId(patientUserId)).thenReturn(Optional.of(patient));
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.findByIdAndPatient_User_Id(appointmentId, patientUserId))
                .thenReturn(Optional.of(appointment));
        when(appointmentRepository.findByIdAndSession_Doctor_User_Id(appointmentId, doctorUserId))
                .thenReturn(Optional.of(appointment));
        when(dataProtectionService.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        when(dataProtectionService.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionQueueService.buildSummary(any())).thenReturn(mock(SessionQueueSummary.class));
        when(notificationRepository.saveAndFlush(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void bookingNotifiesAssignedDoctorWithoutExposingMedicalReason() {
        String sensitiveReason = "Severe chest palpitations and hypertension";
        when(doctorSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(appointmentRepository.existsBySession_IdAndPatient_IdAndStatusIn(any(), any(), any())).thenReturn(false);
        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenAnswer(inv -> {
            Appointment saved = mock(Appointment.class);
            when(saved.getId()).thenReturn(appointmentId);
            when(saved.getAppointmentNumber()).thenReturn(3);
            when(saved.getSession()).thenReturn(session);
            when(saved.getPatient()).thenReturn(patient);
            when(saved.getStatus()).thenReturn(AppointmentStatus.BOOKED);
            when(saved.getAcceptanceStatus()).thenReturn("PENDING");
            when(saved.getPaymentStatus()).thenReturn(PaymentStatus.PENDING);
            return saved;
        });

        appointmentService.book(
                patientUserId,
                new AppointmentBookingRequest(sessionId, sensitiveReason, ConsultationPeriod.MORNING)
        );

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).saveAndFlush(captor.capture());
        Notification doctorNote = captor.getAllValues().stream()
                .filter(n -> n.getUser().getId().equals(doctorUserId))
                .findFirst()
                .orElseThrow();
        assertEquals("New appointment booked", doctorNote.getTitle());
        assertTrue(doctorNote.getMessage().contains("#3"));
        assertFalse(doctorNote.getMessage().contains(sensitiveReason));
        verify(messagingTemplate, times(1))
                .convertAndSendToUser(eq(doctorUserId.toString()), eq("/queue/notifications"), any(NotificationResponse.class));
    }

    @Test
    void doctorAcceptanceNotifiesPatient() {
        when(appointmentRepository.saveAndFlush(appointment)).thenReturn(appointment);

        appointmentService.acceptAppointmentByDoctor(doctorUserId, appointmentId);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        Notification saved = captor.getValue();
        assertEquals(patientUserId, saved.getUser().getId());
        assertEquals("Appointment Accepted", saved.getTitle());
        assertTrue(saved.getMessage().contains("Your appointment has been accepted by the doctor"));
    }

    @Test
    void doctorDeclineNotifiesPatientWithoutPrivateStaffNotes() {
        String privateNotes = "Doctor overbooked; internal scheduling note";
        when(appointmentRepository.saveAndFlush(appointment)).thenReturn(appointment);

        appointmentService.declineAppointmentByDoctor(doctorUserId, appointmentId, privateNotes);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        Notification saved = captor.getValue();
        assertEquals(patientUserId, saved.getUser().getId());
        assertEquals("Appointment Declined", saved.getTitle());
        assertTrue(saved.getMessage().contains("Your appointment could not be accepted"));
        assertFalse(saved.getMessage().contains(privateNotes));
    }

    @Test
    void paymentRecordsExpectedNotificationsForPatientAndDoctor() {
        when(appointment.getAcceptanceStatus()).thenReturn("ACCEPTED");
        when(paymentRepository.existsByAppointment_IdAndStatus(appointmentId, PaymentTransactionStatus.COMPLETED))
                .thenReturn(false);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(appointmentRepository.saveAndFlush(appointment)).thenReturn(appointment);

        paymentService.recordPayment(
                patientUserId,
                "CUSTOMER",
                appointmentId,
                new RecordPaymentRequest(PaymentMethod.CARD)
        );

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).saveAndFlush(captor.capture());
        List<Notification> created = captor.getAllValues();
        assertTrue(created.stream().anyMatch(n ->
                n.getUser().getId().equals(patientUserId) && n.getType() == NotificationType.PAYMENT_RECEIVED));
        assertTrue(created.stream().anyMatch(n ->
                n.getUser().getId().equals(doctorUserId) && n.getType() == NotificationType.PAYMENT_RECEIVED));
    }

    @Test
    void patientCheckInNotifiesAssignedDoctor() {
        when(appointment.getAcceptanceStatus()).thenReturn("ACCEPTED");
        when(appointment.getPaymentStatus()).thenReturn(PaymentStatus.PAID);
        when(appointmentRepository.saveAndFlush(appointment)).thenReturn(appointment);

        appointmentService.checkIn(patientUserId, appointmentId);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        Notification saved = captor.getValue();
        assertEquals(doctorUserId, saved.getUser().getId());
        assertEquals("Patient checked in", saved.getTitle());
        assertTrue(saved.getMessage().contains("has checked in"));
    }

    @Test
    void staffArrivalNotifiesBothPatientAndAssignedDoctor() {
        when(appointment.getStatus()).thenReturn(AppointmentStatus.CHECKED_IN);
        when(appointment.getAcceptanceStatus()).thenReturn("ACCEPTED");
        when(appointment.getPaymentStatus()).thenReturn(PaymentStatus.PAID);
        when(appointmentRepository.saveAndFlush(appointment)).thenReturn(appointment);

        appointmentService.markArrivedByStaff(appointmentId);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).saveAndFlush(captor.capture());
        List<Notification> created = captor.getAllValues();
        assertTrue(created.stream().anyMatch(n ->
                n.getUser().getId().equals(patientUserId)
                        && n.getTitle().equals("Arrival recorded")
                        && n.getMessage().contains("has been recorded")));
        assertTrue(created.stream().anyMatch(n ->
                n.getUser().getId().equals(doctorUserId)
                        && n.getTitle().equals("Patient arrived")
                        && n.getMessage().contains("has arrived")));
    }

    @Test
    void consultationStartNotifiesPatientWithReadyMessage() {
        when(appointment.getStatus()).thenReturn(AppointmentStatus.ARRIVED);
        when(appointment.getAcceptanceStatus()).thenReturn("ACCEPTED");
        when(appointment.getPaymentStatus()).thenReturn(PaymentStatus.PAID);
        when(appointmentRepository.saveAndFlush(appointment)).thenReturn(appointment);

        appointmentService.startConsultation(doctorUserId, appointmentId);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        Notification saved = captor.getValue();
        assertEquals(patientUserId, saved.getUser().getId());
        assertEquals(NotificationType.CONSULTATION_STARTED, saved.getType());
        assertEquals("Your online consultation is ready. You can join now.", saved.getMessage());
    }

    @Test
    void duplicateTransitionAndDuplicateCreateOnceAreRejectedOrSuppressed() {
        when(appointment.getAcceptanceStatus()).thenReturn("ACCEPTED");
        assertThrows(
                ConflictException.class,
                () -> appointmentService.acceptAppointmentByDoctor(doctorUserId, appointmentId)
        );
        verify(notificationRepository, never()).saveAndFlush(any());

        when(notificationRepository.existsByAppointment_IdAndUser_IdAndTypeAndTitle(
                appointmentId, patientUserId, NotificationType.CONSULTATION_STARTED, "Consultation started"
        )).thenReturn(true);

        NotificationResponse duplicate = notificationService.createOnceForAppointment(
                patientUserId,
                NotificationType.CONSULTATION_STARTED,
                "Consultation started",
                "Your online consultation is ready. You can join now.",
                appointmentId
        );
        assertNull(duplicate);
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void unrelatedUsersCannotReadOrMarkOtherUsersNotifications() {
        UUID unrelatedUserId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        when(notificationRepository.findByIdAndUser_Id(notificationId, unrelatedUserId))
                .thenReturn(Optional.empty());

        assertThrows(
                ResourceNotFoundException.class,
                () -> notificationService.markRead(unrelatedUserId, notificationId)
        );
    }
}
