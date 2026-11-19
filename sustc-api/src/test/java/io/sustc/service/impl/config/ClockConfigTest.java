package io.sustc.service.impl.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

    /** A clock that only moves when told to. */
    private static final class ManualClock extends Clock {
        private Instant now;

        ManualClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void withoutAStartTheBaseClockIsUsed() {
        Clock base = Clock.systemUTC();
        assertThat(ClockConfig.clockStartingAt(null, base)).isSameAs(base);
        assertThat(ClockConfig.clockStartingAt("  ", base)).isSameAs(base);
    }

    @Test
    void aStartedClockBeginsAtTheStartAndThenTicksWithTheBaseClock() {
        ManualClock base = new ManualClock(Instant.parse("2026-01-01T00:00:00Z"));

        Clock clock = ClockConfig.clockStartingAt(" 2023-12-30T12:00:00+08:00 ", base);

        assertThat(clock.instant()).isEqualTo(Instant.parse("2023-12-30T04:00:00Z"));
        base.advance(Duration.ofMinutes(90));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2023-12-30T05:30:00Z"));
    }

    @Test
    void anInvalidStartIsRejected() {
        assertThatThrownBy(() -> ClockConfig.clockStartingAt("2023-12-30 12:00", Clock.systemUTC()))
                .isInstanceOf(DateTimeParseException.class);
    }
}
