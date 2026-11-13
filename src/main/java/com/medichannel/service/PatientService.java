package com.medichannel.service;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.patient.PatientProfileRequest;
import com.medichannel.dto.patient.PatientProfileResponse;
import com.medichannel.entity.Patient;
import com.medichannel.entity.User;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ForbiddenOperationException;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;

@Service
public class PatientService {

    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final PatientDataProtectionService protectionService;

    public PatientService(
            PatientRepository patientRepository,
            UserRepository userRepository,
            PatientDataProtectionService protectionService
    ) {
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.protectionService = protectionService;
    }

    @Transactional
    public PatientProfileResponse createProfile(
            UUID userId,
            PatientProfileRequest request
    ) {
        User user = getEligibleCustomer(userId);

        if (patientRepository.findByUserId(userId).isPresent()) {
            throw new ConflictException(
                    "A patient profile already exists for this account."
            );
        }

        String normalizedNic = normalizeNic(request.nic());
        String nicHash = protectionService.createNicLookupHash(normalizedNic);

        if (nicHash != null
                && patientRepository.existsByNicLookupHash(nicHash)) {
            throw new ConflictException(
                    "This NIC is already registered."
            );
        }

        String encryptedFirstName =
                protectionService.encrypt(request.firstName().trim());

        String encryptedLastName =
                protectionService.encrypt(request.lastName().trim());

        String encryptedPhone =
                protectionService.encrypt(normalizePhone(request.phone()));

        String encryptedDateOfBirth =
                protectionService.encrypt(
                        request.dateOfBirth() == null
                                ? null
                                : request.dateOfBirth().toString()
                );

        String encryptedGender =
                protectionService.encrypt(normalizeOptional(request.gender()));

        String normalizedNicValue = normalizedNic;

        Patient patient = new Patient(
                user,
                encryptedFirstName,
                encryptedLastName,
                encryptedPhone,
                nicHash,
                protectionService.encrypt(normalizedNicValue),
                protectionService.encrypt(normalizeOptional(request.address())),
                encryptedDateOfBirth,
                encryptedGender
        );

        Patient savedPatient = patientRepository.saveAndFlush(patient);

        return toResponse(savedPatient);
    }

    @Transactional
    public PatientProfileResponse updateMyProfile(
            UUID userId,
            PatientProfileRequest request
    ) {
        getEligibleCustomer(userId);

        Patient patient = patientRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Patient profile not found."
                ));

        if (request == null) {
            throw new IllegalArgumentException(
                    "Patient profile information is required."
            );
        }

        String normalizedNic = normalizeNic(request.nic());
        String nicHash = normalizedNic == null
                ? patient.getNicLookupHash()
                : protectionService.createNicLookupHash(normalizedNic);
        String nicEncrypted = normalizedNic == null
                ? patient.getNicEncrypted()
                : protectionService.encrypt(normalizedNic);

        if (nicHash != null
                && !nicHash.equals(patient.getNicLookupHash())
                && patientRepository.existsByNicLookupHash(nicHash)) {
            throw new ConflictException(
                    "This NIC is already registered."
            );
        }

        patient.updateProfile(
                protectionService.encrypt(request.firstName().trim()),
                protectionService.encrypt(request.lastName().trim()),
                protectionService.encrypt(normalizePhone(request.phone())),
                nicHash,
                nicEncrypted,
                protectionService.encrypt(normalizeOptional(request.address())),
                protectionService.encrypt(
                        request.dateOfBirth() == null
                                ? null
                                : request.dateOfBirth().toString()
                ),
                protectionService.encrypt(
                        normalizeOptional(request.gender())
                )
        );

        return toResponse(patientRepository.saveAndFlush(patient));
    }

    @Transactional(readOnly = true)
    public PatientProfileResponse getMyProfile(UUID userId) {

        getEligibleCustomer(userId);

        Patient patient = patientRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Patient profile not found."
                ));

        return toResponse(patient);
    }

    private User getEligibleCustomer(UUID userId) {

        User user = userRepository.findByIdWithRole(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Account not found."
                ));

        if (!"CUSTOMER".equals(user.getRole().getName())) {
            throw new ForbiddenOperationException(
                    "Only customer accounts can access patient profiles."
            );
        }

        if (!"ACTIVE".equals(user.getAccountStatus())
                || !user.isEmailVerified()) {
            throw new ForbiddenOperationException(
                    "Your account must be active and verified."
            );
        }

        return user;
    }

    private PatientProfileResponse toResponse(Patient patient) {

        String firstName = protectionService.decrypt(
                patient.getFirstNameEncrypted()
        );

        String lastName = protectionService.decrypt(
                patient.getLastNameEncrypted()
        );

        String phone = protectionService.decrypt(
                patient.getPhoneEncrypted()
        );

        String dateOfBirthValue = protectionService.decrypt(
                patient.getDateOfBirthEncrypted()
        );

        LocalDate dateOfBirth = dateOfBirthValue == null
                ? null
                : LocalDate.parse(dateOfBirthValue);

        String gender = protectionService.decrypt(
                patient.getGenderEncrypted()
        );

        String nic = protectionService.decrypt(
                patient.getNicEncrypted()
        );

        String address = protectionService.decrypt(
                patient.getAddressEncrypted()
        );

        return new PatientProfileResponse(
                patient.getId(),
                firstName,
                lastName,
                phone,
                dateOfBirth,
                gender,
                patient.getCreatedAt(),
                patient.getUpdatedAt(),
                nic,
                address
        );
    }

    private String normalizeNic(String nic) {

        if (nic == null || nic.isBlank()) {
            return null;
        }

        String normalized = nic
                .replaceAll("[\\s-]", "")
                .toUpperCase(Locale.ROOT);

        boolean oldFormat = normalized.matches("[0-9]{9}[VX]");
        boolean newFormat = normalized.matches("[0-9]{12}");

        if (!oldFormat && !newFormat) {
            throw new IllegalArgumentException(
                    "Please provide a valid Sri Lankan NIC number."
            );
        }

        return normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    /**
     * BR-10: Sri Lankan mobile numbers use the 07XXXXXXXX format.
     */
    private String normalizePhone(String phone) {
        String normalized = normalizeOptional(phone);

        if (normalized == null) {
            return null;
        }

        String compact = normalized.replaceAll("[\\s-]", "");

        if (!compact.matches("07[0-9]{8}")) {
            throw new IllegalArgumentException(
                    "Please provide a valid Sri Lankan mobile number "
                            + "in the 07XXXXXXXX format."
            );
        }

        return compact;
    }
}
