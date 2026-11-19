package io.sustc.service.impl.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the {@link Clock} that every service uses for "now".
 *
 * <p>By default this is the system clock. Setting the {@value #START_PROPERTY} property (for
 * example {@code -Dsustc.clock.start=2023-12-30T12:00:00+08:00}) starts a clock at that instant
 * which then ticks at the normal rate. The bundled benchmark data was generated in December 2023
 * and several cases compare a video's public time with "now", so the benchmark pins the clock
 * this way.
 */
@Configuration
@Slf4j
public class ClockConfig {

    static final String START_PROPERTY = "sustc.clock.start";

    @Bean
    public Clock clock(@Value("${" + START_PROPERTY + ":}") String start) {
        return clockStartingAt(start, Clock.systemDefaultZone());
    }

    static Clock clockStartingAt(String start, Clock base) {
        if (start == null || start.isBlank()) {
            return base;
        }
        Instant startInstant = OffsetDateTime.parse(start.trim()).toInstant();
        Duration offset = Duration.between(base.instant(), startInstant);
        log.info("Service clock starts at {} (offset {} from the system clock)", startInstant, offset);
        return Clock.offset(base, offset);
    }
}
