package com.medichannel.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.entity.Appointment;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
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
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.RoomRepository;
import com.medichannel.repository.SpecialisationRepository;
import com.medichannel.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/medichannel_test",
    "spring.datasource.username=postgres",
    "spring.datasource.password=local_test_only_password",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.show-sql=false",
    "spring.jpa.open-in-view=false",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"
})
@Transactional
class AppointmentPostgresIntegrationTests {

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private DoctorSessionRepository doctorSessionRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private SpecialisationRepository specialisationRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldRetrieveAppointmentNumbersGeneratedByPostgresTrigger() {

        String unique = UUID.randomUUID().toString().replace("-", "");
        String shortId = unique.substring(0, 12);

        // 1. Reuse roles seeded by Flyway V1.
        Role doctorRole = roleRepository.findByName("DOCTOR")
                .orElseThrow(() -> new IllegalStateException(
                        "DOCTOR role is missing"
                ));

        Role customerRole = roleRepository.findByName("CUSTOMER")
                .orElseThrow(() -> new IllegalStateException(
                        "CUSTOMER role is missing"
                ));

        // 2. Create a doctor account and profile.
        User doctorUser = userRepository.saveAndFlush(
                new User(
                        doctorRole,
                        "pg.doctor." + unique + "@example.test",
                        "test-password-hash"
                )
        );

        Specialisation specialisation =
                specialisationRepository.saveAndFlush(
                        new Specialisation(
                                "PG Test " + unique,
                                "Temporary PostgreSQL integration test"
                        )
                );

        Doctor doctor = doctorRepository.saveAndFlush(
                new Doctor(
                        doctorUser,
                        specialisation,
                        "Postgres",
                        "TestDoctor",
                        "PG-TEST-" + unique,
                        "Test qualification",
                        "Temporary integration test doctor",
                        new BigDecimal("1500.00")
                )
        );

        // 3. Create two customer accounts and patient profiles.
        User patientUser1 = userRepository.saveAndFlush(
                new User(
                        customerRole,
                        "pg.patient1." + unique + "@example.test",
                        "test-password-hash"
                )
        );

        User patientUser2 = userRepository.saveAndFlush(
                new User(
                        customerRole,
                        "pg.patient2." + unique + "@example.test",
                        "test-password-hash"
                )
        );

        Patient patient1 = patientRepository.saveAndFlush(
                new Patient(
                        patientUser1,
                        "encrypted-first-name-1",
                        "encrypted-last-name-1",
                        null,
                        null,
                        null,
                        null
                )
        );

        Patient patient2 = patientRepository.saveAndFlush(
                new Patient(
                        patientUser2,
                        "encrypted-first-name-2",
                        "encrypted-last-name-2",
                        null,
                        null,
                        null,
                        null
                )
        );

        // 4. Create a physical consultation room.
        Room room = roomRepository.saveAndFlush(
                new Room(
                        "PG-" + shortId,
                        "PostgreSQL Test Room",
                        "Local Test Hospital",
                        RoomType.CONSULTATION,
                        4
                )
        );

        // 5. Create and open a future consultation session.
        DoctorSession session = new DoctorSession(
                doctor,
                room,
                LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(30),
                LocalTime.of(9, 0),
                LocalTime.of(12, 0),
                ConsultationType.PHYSICAL,
                4
        );

        session.updateSchedulingDetails(
                10,
                new BigDecimal("1500.00")
        );

        session.open();

        session = doctorSessionRepository.saveAndFlush(session);

        // 6. Save the first appointment through Hibernate.
        Appointment firstAppointment = new Appointment(
                session,
                patient1,
                "LOCAL_TEST_ENCRYPTED_REASON_1"
        );

        firstAppointment = appointmentRepository.saveAndFlush(
                firstAppointment
        );

        assertThat(firstAppointment.getAppointmentNumber())
                .as("Hibernate must retrieve the first number generated by PostgreSQL")
                .isEqualTo(1);

        // 7. Save the second appointment.
        Appointment secondAppointment = new Appointment(
                session,
                patient2,
                "LOCAL_TEST_ENCRYPTED_REASON_2"
        );

        secondAppointment = appointmentRepository.saveAndFlush(
                secondAppointment
        );

        assertThat(secondAppointment.getAppointmentNumber())
                .as("Hibernate must retrieve the second number generated by PostgreSQL")
                .isEqualTo(2);

        // 8. Verify the actual PostgreSQL session counter.
        Integer databaseCounter = jdbcTemplate.queryForObject(
                """
                SELECT last_appointment_number
                FROM doctor_sessions
                WHERE id = ?
                """,
                Integer.class,
                session.getId()
        );

        assertThat(databaseCounter)
                .as("PostgreSQL appointment counter must match saved appointments")
                .isEqualTo(2);

        // 9. Verify the records can be retrieved from the repository.
        assertThat(
                appointmentRepository.findById(firstAppointment.getId())
        ).isPresent();

        assertThat(
                appointmentRepository.findById(secondAppointment.getId())
        ).isPresent();
    }
}
