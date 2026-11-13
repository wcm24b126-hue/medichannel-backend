package com.medichannel.security;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.medichannel.exception.ApiErrorResponse;
import com.medichannel.service.RateLimitingService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitingFilter extends OncePerRequestFilter {

    private final RateLimitingService rateLimitingService;
    private final ObjectMapper objectMapper;

    public RateLimitingFilter(
            RateLimitingService rateLimitingService,
            ObjectMapper objectMapper
    ) {
        this.rateLimitingService = rateLimitingService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();
        String clientIp = extractClientIp(request);

        // 1. Login rate limit (10 attempts / min per IP)
        if ("POST".equalsIgnoreCase(method) && path.endsWith("/auth/login")) {
            if (!rateLimitingService.tryAcquire("login:" + clientIp, 10)) {
                writeTooManyRequests(request, response, "Login rate limit exceeded. Please wait a minute before retrying.");
                return;
            }
        }

        // 2. Registration rate limit (5 attempts / min per IP)
        if ("POST".equalsIgnoreCase(method) && path.endsWith("/auth/register")) {
            if (!rateLimitingService.tryAcquire("register:" + clientIp, 5)) {
                writeTooManyRequests(request, response, "Registration rate limit exceeded. Please try again later.");
                return;
            }
        }

        // 3. WebSocket ticket exchange (20 requests / min per IP)
        if ("POST".equalsIgnoreCase(method) && path.endsWith("/auth/ws-ticket")) {
            if (!rateLimitingService.tryAcquire("wsticket:" + clientIp, 20)) {
                writeTooManyRequests(request, response, "WebSocket ticket rate limit exceeded.");
                return;
            }
        }

        // 4. Appointment booking (15 requests / min per IP)
        if ("POST".equalsIgnoreCase(method) && path.equals("/api/v1/appointments")) {
            if (!rateLimitingService.tryAcquire("booking:" + clientIp, 15)) {
                writeTooManyRequests(request, response, "Appointment booking rate limit exceeded. Please slow down.");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }

    private void writeTooManyRequests(
            HttpServletRequest request,
            HttpServletResponse response,
            String message
    ) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "60");

        Object reqId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String refId = reqId instanceof String s ? s : UUID.randomUUID().toString();

        ApiErrorResponse error = new ApiErrorResponse(
                false,
                "TOO_MANY_REQUESTS",
                message,
                refId,
                Instant.now()
        );

        objectMapper.writeValue(response.getOutputStream(), error);
    }
}
