package com.medichannel.repository;

import java.math.BigDecimal;

import com.medichannel.entity.Doctor;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Role;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RepositoryIntegrationTests {

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private SpecialisationRepository specialisationRepository;

    @Test
    void shouldSaveAndRetrieveHospitalRecords() {

        // 1. Create and retrieve a customer role
        Role customerRole = roleRepository.saveAndFlush(
                new Role("CUSTOMER", "Registered patient")
        );

        assertThat(roleRepository.findByName("CUSTOMER")).isPresent();
        assertThat(roleRepository.existsByName("CUSTOMER")).isTrue();

        // 2. Create a patient user account
        User patientUser = userRepository.saveAndFlush(
                new User(
                        customerRole,
                        "patient@example.test",
                        "test-password-hash"
                )
        );

        assertThat(userRepository.findByEmail(
                "patient@example.test"
        )).isPresent();

        assertThat(userRepository.existsByEmail(
                "patient@example.test"
        )).isTrue();

        // 3. Create a patient profile
        String nicHash = "a".repeat(64);

        Patient patient = patientRepository.saveAndFlush(
                new Patient(
                        patientUser,
                        "encrypted-first-name",
                        "encrypted-last-name",
                        "encrypted-phone",
                        nicHash,
                        "encrypted-date-of-birth",
                        "encrypted-gender"
                )
        );

        assertThat(patientRepository.findByUserId(
                patientUser.getId()
        )).isPresent();

        assertThat(patientRepository.findByNicLookupHash(
                nicHash
        )).isPresent();

        assertThat(patientRepository.existsByNicLookupHash(
                nicHash
        )).isTrue();

        // 4. Create a medical specialisation
        Specialisation specialisation =
                specialisationRepository.saveAndFlush(
                        new Specialisation(
                                "Cardiology",
                                "Heart and cardiovascular care"
                        )
                );

        assertThat(specialisationRepository.findByName(
                "Cardiology"
        )).isPresent();

        assertThat(specialisationRepository.existsByName(
                "Cardiology"
        )).isTrue();

        assertThat(specialisationRepository.findByActiveTrue())
                .isNotEmpty();

        // 5. Create a doctor user account
        Role doctorRole = roleRepository.saveAndFlush(
                new Role("DOCTOR", "Medical doctor")
        );

        User doctorUser = userRepository.saveAndFlush(
                new User(
                        doctorRole,
                        "doctor@example.test",
                        "test-password-hash"
                )
        );

        // 6. Create a doctor profile
        Doctor doctor = doctorRepository.saveAndFlush(
                new Doctor(
                        doctorUser,
                        specialisation,
                        "Test",
                        "Doctor",
                        "TEST-REG-001",
                        "MBBS",
                        "Test doctor biography",
                        new BigDecimal("2500.00")
                )
        );

        // 7. Verify doctor repository queries
        assertThat(doctorRepository.findByUserId(
                doctorUser.getId()
        )).isPresent();

        assertThat(doctorRepository.findByRegistrationNumber(
                "TEST-REG-001"
        )).isPresent();

        assertThat(doctorRepository.existsByRegistrationNumber(
                "TEST-REG-001"
        )).isTrue();

        assertThat(doctorRepository.findBySpecialisationId(
                specialisation.getId()
        )).isNotEmpty();

        assertThat(doctorRepository.findByAvailableTrue())
                .isNotEmpty();

        assertThat(
                doctorRepository.findBySpecialisationIdAndAvailableTrue(
                        specialisation.getId()
                )
        ).isNotEmpty();

        // Confirm the created records have database IDs
        assertThat(patient.getId()).isNotNull();
        assertThat(doctor.getId()).isNotNull();
    }
}
