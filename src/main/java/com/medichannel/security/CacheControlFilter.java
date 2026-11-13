package com.medichannel.security;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Enforces strict Cache-Control headers on all API responses so personal
 * and clinical health data is never cached in intermediate proxies or browser caches.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class CacheControlFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        if (path.startsWith("/api/")) {
            if (isPublicCatalogue(path, method)) {
                response.setHeader(
                        "Cache-Control",
                        "public, max-age=30, stale-while-revalidate=30"
                );
            } else {
                response.setHeader(
                        "Cache-Control",
                        "no-store, no-cache, must-revalidate, private"
                );
                response.setHeader("Pragma", "no-cache");
                response.setHeader("Expires", "0");
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Only anonymous GET catalogues may be cached. Everything else
     * (appointments, summaries, chat, notifications, staff data) stays
     * no-store.
     */
    private boolean isPublicCatalogue(String path, String method) {
        if (!"GET".equalsIgnoreCase(method)) {
            return false;
        }

        return path.equals("/api/v1/doctors")
                || path.startsWith("/api/v1/doctors?")
                || path.equals("/api/v1/specialisations")
                || path.startsWith("/api/v1/specialisations?")
                || path.equals("/api/v1/sessions")
                || path.startsWith("/api/v1/sessions?");
    }
}
