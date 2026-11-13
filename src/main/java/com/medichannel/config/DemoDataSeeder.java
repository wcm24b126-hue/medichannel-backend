package com.medichannel.config;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.doctor.CreateDoctorRequest;
import com.medichannel.dto.room.RoomRequest;
import com.medichannel.dto.session.DoctorSessionRequest;
import com.medichannel.dto.session.DoctorSessionResponse;
import com.medichannel.dto.specialisation.SpecialisationRequest;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Notice;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Role;
import com.medichannel.entity.Room;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.RoomType;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.NoticeRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.RoomRepository;
import com.medichannel.repository.SpecialisationRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;
import com.medichannel.service.DoctorService;
import com.medichannel.service.DoctorSessionService;
import com.medichannel.service.RoomService;
import com.medichannel.service.SpecialisationService;

/**
 * Optional demo data for a demonstration environment.
 *
 * Disabled unless APP_DEMO_SEED=true. The password never lives in git:
 * it is read from APP_DEMO_PASSWORD and hashed with the application's
 * BCrypt encoder at startup. The runner is idempotent and only logs the
 * accounts it creates, never the password or its hash.
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger logger =
            LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final int MINIMUM_PASSWORD_LENGTH = 8;
    private static final int MAXIMUM_BCRYPT_PASSWORD_BYTES = 72;

    private static final String EMAIL_DOMAIN = "medichannel.demo";

    private static final String[] SPECIALISATIONS = {
            "Cardiology",
            "Neurology",
            "Paediatrics",
            "Dermatology",
            "Orthopaedics",
            "General Medicine",
            "Obstetrics & Gynaecology",
            "ENT Surgery"
    };

    private static final String[] SPECIALISATION_DESCRIPTIONS = {
            "Heart and blood vessel conditions.",
            "Brain, spine and nervous system conditions.",
            "Medical care for infants, children and adolescents.",
            "Skin, hair and nail conditions.",
            "Bones, joints, muscles and sports injuries.",
            "General adult health and preventive care.",
            "Pregnancy, childbirth and women's health.",
            "Ear, nose and throat conditions."
    };

    private static final String[][] DOCTORS = {
            {"Nadeesha", "Wijesinghe", "MBBS, MD (Cardiology)", "0"},
            {"Amaya", "Fernando", "MBBS, MD (Cardiology)", "0"},
            {"Kasun", "Perera", "MBBS, MD (Neurology)", "1"},
            {"Dilani", "Jayawardena", "MBBS, MD (Paediatrics)", "2"},
            {"Ruwan", "Silva", "MBBS, MD (Paediatrics)", "2"},
            {"Ishara", "Bandara", "MBBS, MD (Dermatology)", "3"},
            {"Tharindu", "Gunawardena", "MBBS, MS (Orthopaedics)", "4"},
            {"Malini", "Rathnayake", "MBBS, MD (General Medicine)", "5"},
            {"Sanjeewa", "Dissanayake", "MBBS, MD (General Medicine)", "5"},
            {"Hasini", "Karunaratne", "MBBS, MS (Obstetrics & Gynaecology)", "6"},
            {"Nuwan", "Herath", "MBBS, MS (ENT)", "7"},
            {"Chamari", "Weerasinghe", "MBBS, MD (Dermatology)", "3"}
    };

    // Two decimal fees between LKR 2,500 and 4,500.
    private static final BigDecimal[] FEES = {
            new BigDecimal("4500.00"),
            new BigDecimal("4200.00"),
            new BigDecimal("4000.00"),
            new BigDecimal("3500.00"),
            new BigDecimal("3500.00"),
            new BigDecimal("3000.00"),
            new BigDecimal("4500.00"),
            new BigDecimal("2500.00"),
            new BigDecimal("2500.00"),
            new BigDecimal("4000.00"),
            new BigDecimal("3800.00"),
            new BigDecimal("3000.00")
    };

    private static final String[][] ROOMS = {
            {"R01", "Consultation Room 1", "Ground floor, wing A"},
            {"R02", "Consultation Room 2", "Ground floor, wing A"},
            {"R03", "Consultation Room 3", "Ground floor, wing A"},
            {"R04", "Consultation Room 4", "Ground floor, wing B"},
            {"R05", "Consultation Room 5", "Ground floor, wing B"},
            {"R06", "Consultation Room 6", "First floor, wing A"},
            {"R07", "Consultation Room 7", "First floor, wing A"},
            {"R08", "Consultation Room 8", "First floor, wing B"},
            {"R09", "Procedure Room 1", "Second floor"},
            {"R10", "Consultation Room 10", "Second floor"}
    };

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DoctorRepository doctorRepository;
    private final DoctorSessionRepository doctorSessionRepository;
    private final SpecialisationRepository specialisationRepository;
    private final RoomRepository roomRepository;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final NoticeRepository noticeRepository;
    private final PatientDataProtectionService dataProtectionService;
    private final SpecialisationService specialisationService;
    private final RoomService roomService;
    private final DoctorService doctorService;
    private final DoctorSessionService doctorSessionService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    private final boolean enabled;
    private final String demoPassword;
    private final boolean demoConfigured;

    public DemoDataSeeder(
            UserRepository userRepository,
            RoleRepository roleRepository,
            DoctorRepository doctorRepository,
            DoctorSessionRepository doctorSessionRepository,
            SpecialisationRepository specialisationRepository,
            RoomRepository roomRepository,
            PatientRepository patientRepository,
            AppointmentRepository appointmentRepository,
            NoticeRepository noticeRepository,
            PatientDataProtectionService dataProtectionService,
            SpecialisationService specialisationService,
            RoomService roomService,
            DoctorService doctorService,
            DoctorSessionService doctorSessionService,
            PasswordEncoder passwordEncoder,
            Clock clock,
            @Value("${APP_DEMO_SEED:false}") boolean enabled,
            @Value("${APP_DEMO_PASSWORD:}") String demoPassword
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.doctorRepository = doctorRepository;
        this.doctorSessionRepository = doctorSessionRepository;
        this.specialisationRepository = specialisationRepository;
        this.roomRepository = roomRepository;
        this.patientRepository = patientRepository;
        this.appointmentRepository = appointmentRepository;
        this.noticeRepository = noticeRepository;
        this.dataProtectionService = dataProtectionService;
        this.specialisationService = specialisationService;
        this.roomService = roomService;
        this.doctorService = doctorService;
        this.doctorSessionService = doctorSessionService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.enabled = enabled;
        this.demoPassword = demoPassword;
        this.demoConfigured = demoPassword != null
                && !demoPassword.isBlank();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            logger.info(
                    "Demo data seeding skipped: APP_DEMO_SEED is not true."
            );
            return;
        }

        validatePassword();

        logger.info("Demo data seeding started.");

        List<Specialisation> specialisations = seedSpecialisations();
        List<Room> rooms = seedRooms();

        List<Doctor> doctors = seedDoctors(specialisations);
        seedDemoAdmin();
        seedManager();

        seedSessions(doctors, rooms);
        seedDemoPatientsAndHistory(doctors, rooms);
        seedNotices();

        logger.info(
                "Demo data seeding finished: {} doctors, {} rooms and "
                        + "sessions for the next 7 days are available.",
                doctors.size(),
                rooms.size()
        );
    }

    private void validatePassword() {
        if (!demoConfigured) {
            throw new IllegalStateException(
                    "APP_DEMO_SEED is enabled but APP_DEMO_PASSWORD is not set."
            );
        }

        int passwordBytes = demoPassword
                .getBytes(StandardCharsets.UTF_8)
                .length;

        if (demoPassword.length() < MINIMUM_PASSWORD_LENGTH
                || passwordBytes > MAXIMUM_BCRYPT_PASSWORD_BYTES) {
            throw new IllegalStateException(
                    "APP_DEMO_PASSWORD must contain at least 8 characters "
                            + "and must not exceed 72 UTF-8 bytes."
            );
        }

        if (!demoPassword.matches(
                "(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+"
        )) {
            throw new IllegalStateException(
                    "APP_DEMO_PASSWORD must contain a letter, a number "
                            + "and a symbol."
            );
        }
    }

    /**
     * Creates fictional customer accounts and a past session containing
     * one appointment in every lifecycle status. This gives assessors
     * realistic data for every role and every status without touching
     * production data.
     */
    private void seedDemoPatientsAndHistory(
            List<Doctor> doctors,
            List<Room> rooms
    ) {
        Role customerRole = roleRepository.findByName("CUSTOMER")
                .orElseThrow(() -> new IllegalStateException(
                        "The required CUSTOMER role is not configured."
                ));

        String[][] people = {
                {"Nimal", "Perera", "199012345678", "Colombo 05"},
                {"Sanduni", "Fernando", "199223456789", "Kandy"},
                {"Ruwan", "Jayasinghe", "198834567890", "Galle"},
                {"Tharushi", "Mendis", "199545678901", "Negombo"},
                {"Kasun", "Wickramasinghe", "199356789012", "Matara"},
                {"Anjali", "Dissanayake", "199667890123", "Kurunegala"}
        };

        List<Patient> patients = new ArrayList<>();

        for (int index = 0; index < people.length; index++) {
            String[] person = people[index];
            String email = "patient" + (index + 1) + "@" + EMAIL_DOMAIN;

            User user = userRepository.findByEmail(email).orElse(null);

            if (user == null) {
                user = new User(
                        customerRole,
                        email,
                        passwordEncoder.encode(demoPassword)
                );
                user.activateByAdministrator();
                userRepository.saveAndFlush(user);
                logger.info("Demo patient created: {}", email);
            }

            final User owner = user;
            final int number = index;

            Patient patient = patientRepository.findByUserId(owner.getId())
                    .orElseGet(() -> patientRepository.saveAndFlush(
                            new Patient(
                                    owner,
                                    dataProtectionService.encrypt(
                                            person[0]
                                    ),
                                    dataProtectionService.encrypt(
                                            person[1]
                                    ),
                                    dataProtectionService.encrypt(
                                            "07712345" + (10 + number)
                                    ),
                                    dataProtectionService.createNicLookupHash(
                                            person[2]
                                    ),
                                    dataProtectionService.encrypt(person[2]),
                                    dataProtectionService.encrypt(person[3]),
                                    dataProtectionService.encrypt(
                                            "1990-01-0" + (number + 1)
                                    ),
                                    dataProtectionService.encrypt(
                                            number % 2 == 0
                                                    ? "MALE"
                                                    : "FEMALE"
                                    )
                            )
                    ));

            patients.add(patient);
        }

        if (appointmentRepository.count() > 0) {
            return;
        }

        Doctor doctor = doctors.get(0);
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);

        DoctorSession session = new DoctorSession(
                doctor,
                rooms.get(0),
                yesterday,
                LocalTime.of(9, 0),
                LocalTime.of(12, 0),
                ConsultationType.PHYSICAL,
                12
        );
        session.open();
        doctorSessionRepository.saveAndFlush(session);

        Instant base = yesterday.atTime(9, 0)
                .atZone(TimeConfiguration.APPLICATION_ZONE)
                .toInstant();

        for (int index = 0; index < 6; index++) {
            Appointment appointment = new Appointment(
                    session,
                    patients.get(index % patients.size()),
                    dataProtectionService.encrypt(
                            "Demo consultation reason " + (index + 1)
                    )
            );
            appointment = appointmentRepository.saveAndFlush(appointment);

            switch (index) {
                case 1 -> appointment.markArrived(base.plusSeconds(600));
                case 2 -> {
                    appointment.markArrived(base.plusSeconds(1200));
                    appointment.startConsultation(base.plusSeconds(1500));
                }
                case 3 -> {
                    appointment.markArrived(base.plusSeconds(1800));
                    appointment.startConsultation(base.plusSeconds(2100));
                    appointment.complete(base.plusSeconds(2400));
                }
                case 4 -> appointment.markNoShow();
                case 5 -> appointment.cancelByStaff(base.plusSeconds(3000));
                default -> {
                    // BOOKED is the initial status.
                }
            }

            appointmentRepository.saveAndFlush(appointment);
        }

        logger.info(
                "Demo history seeded: one appointment in every status."
        );

        session.start();
        doctorSessionRepository.saveAndFlush(session);
    }

    private void seedNotices() {
        if (noticeRepository.count() > 0) {
            return;
        }

        User manager = userRepository.findByEmail("manager@" + EMAIL_DOMAIN)
                .orElse(null);

        if (manager == null) {
            return;
        }

        Notice welcome = new Notice(
                "Welcome to MediChannel",
                "Book a doctor, pay securely and join your consultation "
                        + "online. Reception opens 30 minutes before each "
                        + "session.",
                manager
        );
        welcome.publish();
        noticeRepository.save(welcome);

        Notice parking = new Notice(
                "Parking and accessibility",
                "Visitor parking is available on level B1. Wheelchair "
                        + "access is provided at the main entrance and all "
                        + "consultation rooms.",
                manager
        );
        parking.publish();
        noticeRepository.save(parking);

        logger.info("Demo notices seeded.");
    }

    private List<Specialisation> seedSpecialisations() {
        List<Specialisation> result = new ArrayList<>();

        for (int index = 0; index < SPECIALISATIONS.length; index++) {
            String name = SPECIALISATIONS[index];
            String description = SPECIALISATION_DESCRIPTIONS[index];

            Specialisation specialisation = specialisationRepository
                    .findByName(name)
                    .orElseGet(() -> {
                        specialisationService.create(
                                new SpecialisationRequest(
                                        name,
                                        description
                                )
                        );

                        return specialisationRepository
                                .findByName(name)
                                .orElseThrow();
                    });

            result.add(specialisation);
        }

        return result;
    }

    private List<Room> seedRooms() {
        List<Room> result = new ArrayList<>();

        for (String[] details : ROOMS) {
            Room room = roomRepository
                    .findByRoomNumber(details[0])
                    .orElseGet(() -> {
                        roomService.create(new RoomRequest(
                                details[0],
                                details[1],
                                details[2],
                                details[0].startsWith("R09")
                                        ? RoomType.PROCEDURE
                                        : RoomType.CONSULTATION,
                                1
                        ));

                        return roomRepository
                                .findByRoomNumber(details[0])
                                .orElseThrow();
                    });

            result.add(room);
        }

        return result;
    }

    private List<Doctor> seedDoctors(
            List<Specialisation> specialisations
    ) {
        List<Doctor> result = new ArrayList<>();

        for (int index = 0; index < DOCTORS.length; index++) {
            String[] details = DOCTORS[index];
            String firstName = details[0];
            String lastName = details[1];
            String email = emailFor(firstName, lastName);
            String registrationNumber = String.format(
                    Locale.ROOT,
                    "SLMC-%05d",
                    10001 + index
            );

            if (!userRepository.existsByEmail(email)) {
                Specialisation specialisation = specialisations.get(
                        Integer.parseInt(details[3])
                );

                doctorService.createDoctor(new CreateDoctorRequest(
                        email,
                        demoPassword,
                        demoPassword,
                        firstName,
                        lastName,
                        registrationNumber,
                        details[2],
                        specialisation.getId(),
                        FEES[index]
                ));

                User user = userRepository.findByEmail(email)
                        .orElseThrow();

                user.activateAfterVerification();
                userRepository.saveAndFlush(user);

                logger.info("Demo doctor created: {}", email);
            }

            Doctor doctor = doctorRepository
                    .findByRegistrationNumber(registrationNumber)
                    .orElseThrow(() -> new IllegalStateException(
                            "The demo doctor profile could not be created."
                    ));

            result.add(doctor);
        }

        return result;
    }

    private void seedDemoAdmin() {
        String email = "admin@" + EMAIL_DOMAIN;

        if (userRepository.existsByEmail(email)) {
            return;
        }

        Role adminRole = roleRepository.findByName("ADMIN")
                .orElseThrow(() -> new IllegalStateException(
                        "The required ADMIN role is not configured."
                ));

        User admin = new User(
                adminRole,
                email,
                passwordEncoder.encode(demoPassword)
        );
        admin.activateByAdministrator();
        userRepository.saveAndFlush(admin);

        logger.info("Demo administrator created: {}", email);
    }

    private void seedManager() {
        String email = "manager@" + EMAIL_DOMAIN;

        if (userRepository.existsByEmail(email)) {
            return;
        }

        Role managerRole = roleRepository.findByName("MANAGER")
                .orElseThrow(() -> new IllegalStateException(
                        "The required MANAGER role is not configured."
                ));

        User manager = new User(
                managerRole,
                email,
                passwordEncoder.encode(demoPassword)
        );

        manager.activateByAdministrator();

        userRepository.saveAndFlush(manager);

        logger.info("Demo manager created: {}", email);
    }

    private void seedSessions(List<Doctor> doctors, List<Room> rooms) {
        LocalDate today = LocalDate.now(clock);

        for (int dayOffset = 0; dayOffset < 7; dayOffset++) {
            LocalDate date = today.plusDays(dayOffset);

            List<TimeSlot> slots = slotsFor(date, dayOffset == 0);

            if (slots.isEmpty()) {
                continue;
            }

            for (int index = 0; index < doctors.size(); index++) {
                // Stagger the roster so every day has a different mix of
                // doctors, while each doctor still appears several times.
                if ((index + dayOffset) % 2 != 0) {
                    continue;
                }

                Doctor doctor = doctors.get(index);

                if (!doctorSessionRepository
                        .findByDoctor_IdAndSessionDate(
                                doctor.getId(),
                                date
                        ).isEmpty()) {
                    continue;
                }

                TimeSlot slot = slots.get((index / 2) % slots.size());

                boolean online = index >= rooms.size()
                        || (index + dayOffset) % 3 == 0;

                UUID roomId = online
                        ? null
                        : rooms.get(index % rooms.size()).getId();

                DoctorSessionResponse session =
                        doctorSessionService.createSession(
                                new DoctorSessionRequest(
                                        doctor.getId(),
                                        roomId,
                                        date,
                                        slot.start(),
                                        slot.end(),
                                        online
                                                ? ConsultationType.ONLINE
                                                : ConsultationType.PHYSICAL,
                                        slot.maxAppointments(),
                                        slot.minutesPerPatient(),
                                        FEES[index]
                                )
                        );

                // createSession already opens the session for booking.
            }
        }
    }

    /**
     * Future, bookable slots. The two booking cut-offs (60 minutes for
     * physical and 30 minutes for online) are respected because today's
     * slot always starts at least 75 minutes from now.
     */
    private List<TimeSlot> slotsFor(LocalDate date, boolean isToday) {
        List<TimeSlot> slots = new ArrayList<>();

        if (!isToday) {
            slots.add(new TimeSlot(
                    LocalTime.of(9, 0),
                    LocalTime.of(12, 0),
                    16,
                    10
            ));
            slots.add(new TimeSlot(
                    LocalTime.of(14, 0),
                    LocalTime.of(17, 0),
                    16,
                    10
            ));
            slots.add(new TimeSlot(
                    LocalTime.of(18, 0),
                    LocalTime.of(21, 0),
                    12,
                    15
            ));
            return slots;
        }

        LocalDateTime now = LocalDateTime.now(clock);

        LocalDateTime start = now
                .plusMinutes(75)
                .truncatedTo(ChronoUnit.MINUTES);

        int minute = start.getMinute();
        int rounded = ((minute + 14) / 15) * 15;

        if (rounded == 60) {
            start = start.plusHours(1)
                    .withMinute(0)
                    .withSecond(0)
                    .withNano(0);
        } else {
            start = start.withMinute(rounded)
                    .withSecond(0)
                    .withNano(0);
        }

        LocalDateTime end = start.plusMinutes(110);

        LocalTime startTime = start.toLocalTime();
        LocalTime endTime;
        if (end.toLocalDate().isAfter(date)) {
            endTime = LocalTime.of(23, 59);
        } else {
            endTime = end.toLocalTime();
        }

        slots.add(new TimeSlot(
                LocalTime.of(9, 0),
                LocalTime.of(12, 0),
                16,
                10
        ));
        slots.add(new TimeSlot(
                LocalTime.of(14, 0),
                LocalTime.of(17, 0),
                16,
                10
        ));
        slots.add(new TimeSlot(
                LocalTime.of(18, 0),
                LocalTime.of(21, 0),
                12,
                15
        ));

        if (!start.toLocalDate().isAfter(date)
                && startTime.isBefore(endTime)
                && startTime.isAfter(LocalTime.of(6, 0))) {
            long availableMinutes = java.time.Duration
                    .between(startTime, endTime)
                    .toMinutes();
            int maxAppointments = (int) Math.max(
                    1,
                    availableMinutes / 15
            );

            slots.add(new TimeSlot(
                    startTime,
                    endTime,
                    maxAppointments,
                    15
            ));
        }

        // Today's fixed slots may already be in the past; the booking
        // validation rejects them, so drop any that cannot be booked.
        LocalDateTime earliestBookable = now.plusMinutes(75);
        slots.removeIf(slot -> date.atTime(slot.start())
                .isBefore(earliestBookable));

        return slots;
    }

    private String emailFor(String firstName, String lastName) {
        return (firstName + "." + lastName)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.]", "")
                + "@" + EMAIL_DOMAIN;
    }

    private record TimeSlot(
            LocalTime start,
            LocalTime end,
            int maxAppointments,
            int minutesPerPatient
    ) {
    }
}
