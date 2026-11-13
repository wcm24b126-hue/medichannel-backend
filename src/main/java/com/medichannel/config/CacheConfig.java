package com.medichannel.config;

import java.time.Duration;
import java.util.List;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Week 16 performance requirement: only the public doctor and
 * specialisation catalogues are cached. Authenticated and clinical
 * responses must never be cached (see CacheControlFilter).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String PUBLIC_DOCTORS = "publicDoctors";
    public static final String PUBLIC_SPECIALISATIONS =
            "publicSpecialisations";

    public static final Duration TTL = Duration.ofSeconds(60);

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCacheNames(List.of(
                PUBLIC_DOCTORS,
                PUBLIC_SPECIALISATIONS
        ));
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(500)
                .recordStats());
        return manager;
    }
}
