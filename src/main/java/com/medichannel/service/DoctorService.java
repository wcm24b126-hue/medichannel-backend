package com.medichannel.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.doctor.CreateDoctorRequest;
import com.medichannel.dto.doctor.DoctorAvailabilityRequest;
import com.medichannel.dto.doctor.DoctorCreatedResponse;
import com.medichannel.dto.doctor.DoctorResponse;
import com.medichannel.dto.doctor.UpdateDoctorRequest;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.Role;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.SpecialisationRepository;
import com.medichannel.repository.UserRepository;

@Service
public class DoctorService {

    private static final String DOCTOR_ROLE = "DOCTOR";
    private static final int MAX_BCRYPT_PASSWORD_BYTES = 72;

    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SpecialisationRepository specialisationRepository;
    private final PasswordEncoder passwordEncoder;

    public DoctorService(
            DoctorRepository doctorRepository,
            UserRepository userRepository,
            RoleRepository roleRepository,
            SpecialisationRepository specialisationRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.doctorRepository = doctorRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.specialisationRepository = specialisationRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /*
     * Creates the DOCTOR user and doctor profile in one transaction.
     * The new account remains PENDING and emailVerified=false.
     */
    @Transactional
    public DoctorCreatedResponse createDoctor(CreateDoctorRequest request) {

        String email = normalizeEmail(request.email());
        String registrationNumber =
                normalizeRegistrationNumber(request.registrationNumber());

        validatePassword(request.initialPassword(), request.confirmPassword());

        if (userRepository.existsByEmail(email)) {
            throw new ConflictException(
                    "An account with this email already exists."
            );
        }

        if (doctorRepository.existsByRegistrationNumber(registrationNumber)) {
            throw new ConflictException(
                    "A doctor with this registration number already exists."
            );
        }

        Specialisation specialisation =
                findActiveSpecialisation(request.specialisationId());

        Role doctorRole = roleRepository.findByName(DOCTOR_ROLE)
                .orElseThrow(() -> new IllegalStateException(
                        "The required DOCTOR role is not configured."
                ));

        User user = new User(
                doctorRole,
                email,
                passwordEncoder.encode(request.initialPassword())
        );

        User savedUser = userRepository.saveAndFlush(user);

        Doctor doctor = new Doctor(
                savedUser,
                specialisation,
                normalizeRequiredText(request.firstName(), "First name"),
                normalizeRequiredText(request.lastName(), "Last name"),
                registrationNumber,
                normalizeOptionalText(request.qualification()),
                null,
                request.consultationFee()
        );

        Doctor savedDoctor = doctorRepository.saveAndFlush(doctor);

        return new DoctorCreatedResponse(
                "Doctor account and profile created. The account is pending activation.",
                savedDoctor.getId(),
                savedUser.getId(),
                savedUser.getEmail(),
                savedUser.getAccountStatus(),
                savedDoctor.getRegistrationNumber()
        );
    }

    @Transactional(readOnly = true)
    public List<DoctorResponse> getPublicDirectory(UUID specialisationId) {

        List<Doctor> doctors;

        if (specialisationId == null) {
            doctors = doctorRepository.findPublicDirectory();
        } else {
            doctors = doctorRepository
                    .findPublicDirectoryBySpecialisation(specialisationId);
        }

        return doctors.stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DoctorResponse getPublicDoctor(UUID doctorId) {
        Doctor doctor = doctorRepository.findPublicDoctorById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Doctor not found."
                ));

        return toResponse(doctor);
    }

    @Transactional
    public DoctorResponse updateDoctor(
            UUID doctorId,
            UpdateDoctorRequest request
    ) {
        Doctor doctor = findDoctor(doctorId);

        String registrationNumber =
                normalizeRegistrationNumber(request.registrationNumber());

        doctorRepository.findByRegistrationNumber(registrationNumber)
                .filter(existing -> !existing.getId().equals(doctorId))
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "A doctor with this registration number already exists."
                    );
                });

        Specialisation specialisation =
                findActiveSpecialisation(request.specialisationId());

        doctor.updateProfile(
                specialisation,
                normalizeRequiredText(request.firstName(), "First name"),
                normalizeRequiredText(request.lastName(), "Last name"),
                registrationNumber,
                normalizeOptionalText(request.qualification()),
                doctor.getBiography(),
                request.consultationFee()
        );

        return toResponse(doctor);
    }

    @Transactional
    public DoctorResponse updateAvailability(
            UUID doctorId,
            DoctorAvailabilityRequest request
    ) {
        Doctor doctor = findDoctor(doctorId);
        doctor.updateAvailability(request.available());
        return toResponse(doctor);
    }

    @Transactional(readOnly = true)
    public DoctorResponse getProfileForUser(UUID userId) {
        Doctor doctor = doctorRepository
                .findByUserIdWithSpecialisation(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Doctor profile not found."
                ));

        return toResponse(doctor);
    }

    private Doctor findDoctor(UUID doctorId) {
        return doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Doctor not found."
                ));
    }

    private Specialisation findActiveSpecialisation(UUID id) {
        return specialisationRepository.findById(id)
                .filter(Specialisation::isActive)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Active specialisation not found."
                ));
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required.");
        }

        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeRegistrationNumber(String number) {
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException(
                    "Registration number is required."
            );
        }

        return number.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " is required."
            );
        }

        return value.trim();
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private void validatePassword(String password, String confirmPassword) {
        if (password == null || password.isBlank()
                || confirmPassword == null || confirmPassword.isBlank()) {
            throw new IllegalArgumentException(
                    "Password and password confirmation are required."
            );
        }

        if (!password.equals(confirmPassword)) {
            throw new IllegalArgumentException(
                    "Password and confirmation do not match."
            );
        }

        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;

        if (password.length() < 12 || passwordBytes > MAX_BCRYPT_PASSWORD_BYTES) {
            throw new IllegalArgumentException(
                    "Password must contain at least 12 characters and must not exceed 72 UTF-8 bytes."
            );
        }
    }

    private DoctorResponse toResponse(Doctor doctor) {
        return new DoctorResponse(
                doctor.getId(),
                doctor.getFirstName(),
                doctor.getLastName(),
                doctor.getRegistrationNumber(),
                doctor.getQualification(),
                doctor.getBiography(),
                doctor.getSpecialisation().getName(),
                doctor.getSpecialisation().getId(),
                doctor.getConsultationFee(),
                doctor.isAvailable(),
                doctor.getCreatedAt()
        );
    }
}
