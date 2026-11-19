package io.sustc.service.impl.support;

import java.sql.Timestamp;
import java.time.Clock;

/** The services' notion of "now", always read from the injected {@link Clock}. */
public final class Timestamps {

    private Timestamps() {}

    public static Timestamp now(Clock clock) {
        return Timestamp.from(clock.instant());
    }
}
