package com.medichannel.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.medichannel.dto.audit.AuditLogResponse;
import com.medichannel.dto.common.PagedResponse;
import com.medichannel.entity.AuditLog;
import com.medichannel.entity.User;
import com.medichannel.repository.AuditLogRepository;
import com.medichannel.repository.UserRepository;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Append-only audit trail service.
 *
 * Audit events identify actors by user id (never by plaintext email) and
 * carry an action, non-medical details, a timestamp and the best available
 * client IP address. V19 installs database triggers that make audit rows
 * immutable.
 */
@Service
public class AuditLogService {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private static final String UNKNOWN_IP = "unknown";
    private static final int MAX_IP_LENGTH = 100;
    private static final int MAX_ROLE_LENGTH = 50;
    private static final Set<String> PLACEHOLDER_IPS =
            Set.of("0.0.0.0", "unknown", "null", "-", "n/a");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final ObjectProvider<SimpMessagingTemplate> messagingTemplateProvider;
    private final List<IpRange> trustedProxyRanges;

    public AuditLogService(
            AuditLogRepository auditLogRepository,
            ObjectProvider<SimpMessagingTemplate> messagingTemplateProvider,
            UserRepository userRepository,
            @Value("${AUDIT_TRUSTED_PROXIES:127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}")
            String trustedProxies
    ) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.messagingTemplateProvider = messagingTemplateProvider;
        this.trustedProxyRanges = parseTrustedProxies(trustedProxies);
    }

    // ------------------------------------------------------------------
    // Public audit API
    // ------------------------------------------------------------------

    /**
     * Records a login-related event for AuthService.
     *
     * @param userId    authenticated user id, or null for a failed/unknown login
     * @param email     submitted email, used only to resolve the user id when
     *                  {@code userId} is null; never persisted
     * @param action    e.g. LOGIN_SUCCESS / LOGIN_FAILED / LOGIN_LOCKED
     * @param ipAddress client IP, or null to resolve it from the current request
     * @param details   non-medical, non-email event details
     */
    @Transactional
    public AuditLog logLogin(
            UUID userId,
            String email,
            String action,
            String ipAddress,
            String details
    ) {
        UUID actorId = userId != null ? userId : resolveUserIdByEmail(email);
        return log(
                actorId,
                resolveRole(actorId, null),
                action,
                details,
                ipAddress,
                null
        );
    }

    /** Login event for an already known user id. */
    @Transactional
    public AuditLog logLogin(
            UUID userId,
            String action,
            String ipAddress,
            String details
    ) {
        return logLogin(userId, null, action, ipAddress, details);
    }

    /**
     * Records a successful consultation record opening. No clinical content,
     * message text or email address is included in the audit row.
     *
     * @param action one of CONSULTATION_SUMMARY_VIEWED / CHAT_HISTORY_VIEWED
     */
    @Transactional
    public AuditLog logConsultationView(
            UUID actorUserId,
            String action,
            UUID appointmentId,
            String ipAddress
    ) {
        if (appointmentId == null) {
            throw new IllegalArgumentException("Audit appointment id is required.");
        }

        String details =
                "Consultation record opened (appointment=" + appointmentId + ")";

        return log(
                actorUserId,
                resolveRole(actorUserId, null),
                action,
                details,
                ipAddress,
                appointmentId
        );
    }

    /** Appends an audit event identified by actor id (never by email). */
    @Transactional
    public AuditLog log(
            UUID actorUserId,
            String userRole,
            String action,
            String details,
            String ipAddress
    ) {
        return log(actorUserId, userRole, action, details, ipAddress, null);
    }

    /** Appends an audit event linked to an appointment. */
    @Transactional
    public AuditLog log(
            UUID actorUserId,
            String userRole,
            String action,
            String details,
            String ipAddress,
            UUID appointmentId
    ) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("Audit action is required.");
        }

        AuditLog auditLog = new AuditLog(
                actorUserId,
                normalizeRole(userRole),
                action.trim(),
                sanitizeDetails(details),
                resolveClientIpAddress(ipAddress),
                appointmentId
        );

        AuditLog saved = auditLogRepository.save(auditLog);

        // Broadcast the append-only, privacy-safe event to administrators.
        try {
            SimpMessagingTemplate template = messagingTemplateProvider.getIfAvailable();
            if (template != null) {
                template.convertAndSend(
                        "/topic/audit-logs",
                        toResponse(saved)
                );
            }
        } catch (Exception e) {
            log.warn("Failed to broadcast audit log event via WebSocket: {}",
                    e.getMessage());
        }

        return saved;
    }

    /**
     * Backwards-compatible adapter for services that still pass an email as
     * the first argument. The email is resolved to a user id and is never
     * written to the audit log.
     *
     * @deprecated use {@link #log(UUID, String, String, String, String)} or
     *             {@link #logLogin(UUID, String, String, String, String)}
     */
    @Deprecated(forRemoval = true)
    @Transactional
    public AuditLog log(
            String userEmail,
            String userRole,
            String action,
            String details,
            String ipAddress
    ) {
        return log(
                resolveUserIdByEmail(userEmail),
                userRole,
                action,
                details,
                ipAddress,
                null
        );
    }

    public void validatePagination(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("Page index must not be negative.");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than zero.");
        }
        if (size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Page size must not exceed " + MAX_PAGE_SIZE + "."
            );
        }
    }

    @Transactional(readOnly = true)
    public PagedResponse<AuditLogResponse> getAuditLogs(int page, int size, UUID userId) {
        validatePagination(page, size);
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.DESC, "id"));
        Pageable pageable = PageRequest.of(page, size, sort);
        Page<AuditLog> logs = userId != null
                ? auditLogRepository.findByUserId(userId, pageable)
                : auditLogRepository.findAll(pageable);
        return PagedResponse.from(logs.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public PagedResponse<AuditLogResponse> getAuditLogs(int page, int size) {
        return getAuditLogs(page, size, null);
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> getRecentAuditLogs() {
        return auditLogRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    // ------------------------------------------------------------------
    // Client IP resolution
    // ------------------------------------------------------------------

    /**
     * Returns an explicit IP if usable; otherwise derives the client IP from
     * the current HTTP request. Forwarded headers are only honoured when the
     * immediate peer is a configured trusted proxy.
     */
    public String resolveClientIpAddress(String suppliedIpAddress) {
        if (!isPlaceholder(suppliedIpAddress)) {
            return normalizeIp(suppliedIpAddress);
        }

        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return UNKNOWN_IP;
        }

        HttpServletRequest request = servletAttributes.getRequest();
        String remoteAddr = normalizeIp(request.getRemoteAddr());

        if (isTrustedProxy(remoteAddr)) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return normalizeIp(chooseForwardedClient(forwardedFor, remoteAddr));
            }

            String realIp = firstToken(request.getHeader("X-Real-IP"));
            if (realIp != null) {
                return normalizeIp(realIp);
            }
        }

        return remoteAddr;
    }

    private String chooseForwardedClient(
            String forwardedFor,
            String remoteAddr
    ) {
        List<String> hops = Arrays.stream(forwardedFor.split(","))
                .map(String::trim)
                .filter(hop -> !hop.isEmpty())
                .toList();

        if (hops.isEmpty()) {
            return remoteAddr;
        }

        // Walk right-to-left so a client-supplied leftmost entry cannot
        // override a hop inserted by a trusted proxy.
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (!isTrustedProxy(hop)) {
                return hop;
            }
        }

        return hops.get(0);
    }

    private boolean isTrustedProxy(String address) {
        if (isPlaceholder(address)) {
            return false;
        }

        try {
            byte[] candidate = InetAddress.getByName(address).getAddress();
            for (IpRange range : trustedProxyRanges) {
                if (range.matches(candidate)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }

        return false;
    }

    private String firstToken(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.split(",", 2)[0].trim();
    }

    private String normalizeIp(String value) {
        if (isPlaceholder(value)) {
            return UNKNOWN_IP;
        }

        String candidate = value.trim()
                .replaceAll("[\\r\\n\\t]", "");

        if (candidate.startsWith("[") && candidate.endsWith("]")
                && candidate.length() > 2) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }

        int space = candidate.indexOf(' ');
        if (space >= 0) {
            candidate = candidate.substring(0, space);
        }

        if (candidate.length() > MAX_IP_LENGTH) {
            candidate = candidate.substring(0, MAX_IP_LENGTH);
        }

        return isPlaceholder(candidate) ? UNKNOWN_IP : candidate;
    }

    private boolean isPlaceholder(String value) {
        return value == null
                || value.isBlank()
                || PLACEHOLDER_IPS.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private UUID resolveUserIdByEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }

        String normalized = email.trim().toLowerCase(Locale.ROOT);
        try {
            return userRepository.findByEmail(normalized)
                    .map(User::getId)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not resolve audit actor id from email: {}",
                    e.getMessage());
            return null;
        }
    }

    private String resolveRole(UUID userId, String fallback) {
        if (userId == null) {
            return fallback;
        }

        try {
            return userRepository.findByIdWithRole(userId)
                    .map(User::getRole)
                    .map(role -> role.getName())
                    .orElse(fallback);
        } catch (RuntimeException e) {
            log.warn("Could not resolve audit actor role: {}", e.getMessage());
            return fallback;
        }
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }

        String trimmed = role.trim();
        return trimmed.length() > MAX_ROLE_LENGTH
                ? trimmed.substring(0, MAX_ROLE_LENGTH)
                : trimmed;
    }

    /** Removes email addresses from legacy details strings before persistence. */
    private String sanitizeDetails(String details) {
        if (details == null) {
            return null;
        }
        return EMAIL_PATTERN.matcher(details).replaceAll("[redacted-email]");
    }

    private AuditLogResponse toResponse(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getCreatedAt(),
                log.getUserId(),
                log.getUserRole(),
                log.getAppointmentId(),
                log.getAction(),
                log.getDetails(),
                log.getIpAddress()
        );
    }

    private List<IpRange> parseTrustedProxies(String configured) {
        List<IpRange> ranges = new ArrayList<>();
        if (configured == null || configured.isBlank()) {
            return ranges;
        }

        for (String entry : configured.split(",")) {
            String value = entry.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                ranges.add(new IpRange(value));
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring invalid AUDIT_TRUSTED_PROXIES entry '{}': {}",
                        value, e.getMessage());
            }
        }

        return ranges;
    }

    /** IPv4/IPv6 address plus prefix length, used for trusted proxy checks. */
    private static final class IpRange {

        private final byte[] address;
        private final int prefixLength;

        private IpRange(String value) {
            int slash = value.lastIndexOf('/');
            String base = slash < 0 ? value : value.substring(0, slash);

            try {
                this.address = InetAddress.getByName(base).getAddress();
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid IP address", e);
            }

            int maxBits = this.address.length * 8;
            int parsedPrefix = slash < 0
                    ? maxBits
                    : Integer.parseInt(value.substring(slash + 1).trim());

            if (parsedPrefix < 0 || parsedPrefix > maxBits) {
                throw new IllegalArgumentException(
                        "Prefix length must be between 0 and " + maxBits
                );
            }

            this.prefixLength = parsedPrefix;
        }

        private boolean matches(byte[] candidate) {
            if (candidate.length != address.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != address[i]) {
                    return false;
                }
            }

            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }

            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask)
                    == (address[fullBytes] & mask);
        }
    }
}
