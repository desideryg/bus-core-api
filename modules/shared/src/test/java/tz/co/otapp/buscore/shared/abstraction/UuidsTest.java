package tz.co.otapp.buscore.shared.abstraction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tz.co.otapp.buscore.shared.time.Times;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bit layout is the contract here, not an implementation detail.
 *
 * <p>Every {@code uid} column in the system carries a unique index, and the reason for choosing version 7
 * over version 4 is that the leading timestamp keeps those indexes dense. A regression that quietly emitted
 * version 4 values would break nothing visible — no test would fail, no request would error — while every
 * index in the database slowly degraded. So the layout is asserted directly.
 */
class UuidsTest {

    @AfterEach
    void restoreClock() {
        // Unconditional: a leaked fixed clock makes a later, unrelated test see a stale "now".
        Times.reset();
    }

    @Test
    @DisplayName("declares version 7 and the RFC 4122 variant")
    void bit_layout_is_correct() {
        UUID uid = Uuids.next();

        assertThat(uid.version())
                .as("version 4 would compile, pass every other test, and quietly degrade every uid index")
                .isEqualTo(7);
        assertThat(uid.variant())
                .as("variant 2 is the RFC 4122 layout; anything else is not a well-formed UUID")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the leading 48 bits are the current time in milliseconds")
    void embeds_the_clock() {
        Instant when = Instant.parse("2026-07-23T10:15:30Z");
        Times.fixedAt(when);

        long embedded = Uuids.next().getMostSignificantBits() >>> 16;

        assertThat(embedded).isEqualTo(when.toEpochMilli());
    }

    @Test
    @DisplayName("values minted in increasing milliseconds sort in that order")
    void is_time_ordered() {
        List<UUID> minted = new ArrayList<>();
        Instant start = Instant.parse("2026-07-23T10:15:30Z");
        for (int i = 0; i < 50; i++) {
            Times.fixedAt(start.plusMillis(i));
            minted.add(Uuids.next());
        }

        // toString ordering is what a database index on a uuid column effectively preserves, and it is the
        // ordering that makes inserts append rather than scatter.
        List<String> asStrings = minted.stream().map(UUID::toString).toList();
        assertThat(asStrings).isSorted();
    }

    @Test
    @DisplayName("values within one millisecond are unique, though not ordered")
    void is_unique_within_a_millisecond() {
        Times.fixedAt(Instant.parse("2026-07-23T10:15:30Z"));

        Set<UUID> minted = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            minted.add(Uuids.next());
        }

        // 74 random bits per value; a collision in ten thousand would mean the randomness is broken, which
        // is a far more serious defect than the lack of intra-millisecond ordering the design accepts.
        assertThat(minted).hasSize(10_000);
    }
}
