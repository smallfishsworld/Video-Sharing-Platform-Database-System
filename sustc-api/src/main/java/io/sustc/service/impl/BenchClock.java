package io.sustc.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Benchmark-only clock shim. The bundled test data was generated in late 2023 and some cases
 * (post/update/review/delete video) depend on "now". Setting -Dsustc.clock.start=2023-12-30T12:00:00+08:00
 * shifts the service clock so the expected answers stay valid; the shift is a constant offset, so it adds
 * no measurable overhead.
 */
final class BenchClock {
    private static final Duration OFFSET;

    static {
        String start = System.getProperty("sustc.clock.start");
        OFFSET = start == null ? Duration.ZERO : Duration.between(Instant.now(), OffsetDateTime.parse(start).toInstant());
    }

    private BenchClock() {
    }

    static Instant now() {
        return Instant.now().plus(OFFSET);
    }
}
