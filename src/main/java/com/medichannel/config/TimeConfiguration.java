package com.medichannel.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfiguration {

    public static final ZoneId APPLICATION_ZONE =
            ZoneId.of("Asia/Colombo");

    @Bean
    public Clock applicationClock() {
        return Clock.system(APPLICATION_ZONE);
    }
}
