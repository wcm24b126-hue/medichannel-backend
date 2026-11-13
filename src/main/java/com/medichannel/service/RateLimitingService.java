package com.medichannel.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.medichannel.exception.TooManyRequestsException;

@Service
public class RateLimitingService {

    private static final Logger logger =
            LoggerFactory.getLogger(RateLimitingService.class);

    private static final long WINDOW_MS = 60_000L; // 1 minute window

    private final DataSource dataSource;

    // Fast local memory cache to absorb microbursts
    private final ConcurrentHashMap<String, AtomicInteger> localCache =
            new ConcurrentHashMap<>();
    private volatile long currentBucket = System.currentTimeMillis() / WINDOW_MS;

    public RateLimitingService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Check rate limit and throw TooManyRequestsException if exceeded.
     */
    public void checkRateLimit(
            String key,
            int maxRequestsPerMinute,
            String actionName
    ) {
        if (!tryAcquire(key, maxRequestsPerMinute)) {
            logger.warn("Rate limit exceeded for key '{}' on action '{}'", key, actionName);
            throw new TooManyRequestsException(
                    "Too many requests for " + actionName + ". Please wait before trying again.",
                    60
            );
        }
    }

    /**
     * Attempts to acquire a rate limit slot. Returns true if allowed, false if exceeded.
     */
    public boolean tryAcquire(String key, int maxRequestsPerMinute) {
        long bucket = System.currentTimeMillis() / WINDOW_MS;

        // Reset local cache on bucket rollover
        if (bucket != currentBucket) {
            currentBucket = bucket;
            localCache.clear();
        }

        // Check local microburst buffer
        AtomicInteger localCounter = localCache.computeIfAbsent(
                key,
                k -> new AtomicInteger(0)
        );

        if (localCounter.incrementAndGet() > maxRequestsPerMinute * 2) {
            return false;
        }

        // Synchronize with database for distributed Render instances
        String sql = "INSERT INTO api_rate_limits (rate_key, window_bucket, request_count, created_at) "
                + "VALUES (?, ?, 1, CURRENT_TIMESTAMP) "
                + "ON CONFLICT (rate_key, window_bucket) "
                + "DO UPDATE SET request_count = api_rate_limits.request_count + 1 "
                + "RETURNING request_count;";

        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, key);
            ps.setLong(2, bucket);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int count = rs.getInt(1);
                    return count <= maxRequestsPerMinute;
                }
            }

        } catch (Exception e) {
            // If DB query fails, fall back gracefully to local counter
            logger.warn("Database rate-limit check failed, falling back to local tracking: {}", e.getMessage());
            return localCounter.get() <= maxRequestsPerMinute;
        }

        return true;
    }

    /**
     * Periodic cleanup of expired rate limit windows (runs every hour).
     */
    @Scheduled(fixedRate = 3600_000)
    public void purgeOldLimits() {
        String sql = "DELETE FROM api_rate_limits WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '1 hour';";
        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                logger.debug("Purged {} expired rate-limit records.", deleted);
            }
        } catch (Exception e) {
            logger.warn("Could not purge old rate limits: {}", e.getMessage());
        }
    }
}
