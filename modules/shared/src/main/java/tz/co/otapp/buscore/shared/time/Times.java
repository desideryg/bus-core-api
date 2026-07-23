package tz.co.otapp.buscore.shared.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * The one source of "now".
 *
 * <p>Everything is UTC. Timestamps are stored as instants with no zone offset, and a local wall-clock time
 * is resolved at query time against the zone the reader cares about — never by storing a zoned value and
 * trusting every writer to have used the same zone.
 *
 * <h2>Why a static holder, when injection is the usual answer</h2>
 *
 * <p>Injecting a {@link Clock} bean would be the tidier design, and it is the right one for services. But
 * the timestamps on {@code BaseEntity} are set from a JPA lifecycle callback, and <b>JPA instantiates
 * entities itself — there is no injection point</b>. The alternatives were worse: Spring Data auditing
 * would pull the JPA starter into this module and, through it, an entity-manager autoconfiguration onto the
 * classpath of everything that depends on shared, including a deployable that has no database yet.
 *
 * <p>So the clock is static and swappable, and the cost is stated rather than hidden: this is global
 * mutable state, and a test that moves it must put it back.
 *
 * <h2>Why any of this matters</h2>
 *
 * <p>Every flow with an expiry, a lockout window, a rotation deadline or a retry backoff is untestable
 * against a clock that cannot be moved. The alternative is a test that sleeps — which is slow, flaky, and
 * cannot reach the case fifteen minutes from now at all.
 */
public final class Times {

    /** Volatile: a test on one thread may move the clock for code running on another. */
    private static volatile Clock clock = Clock.systemUTC();

    private Times() {
    }

    /** The current instant, in UTC. Use this rather than {@code Instant.now()} anywhere time is a rule. */
    public static Instant now() {
        return clock.instant();
    }

    /** The clock itself, for code that needs a {@link Clock} rather than a single reading. */
    public static Clock clock() {
        return clock;
    }

    /**
     * Freeze time at a fixed instant. <b>Test use only.</b>
     *
     * <p>Always restore it — a leaked fixed clock makes every later test in the same JVM see a stale "now",
     * and the failure surfaces somewhere unrelated:
     *
     * <pre>
     * &#64;AfterEach void restoreClock() { Times.reset(); }
     * </pre>
     */
    public static void fixedAt(Instant instant) {
        clock = Clock.fixed(instant, ZoneOffset.UTC);
    }

    /** Move a frozen clock forward. Test use only; requires {@link #fixedAt} to have been called. */
    public static void advanceTo(Instant instant) {
        fixedAt(instant);
    }

    /** Restore the real clock. Call this from test teardown, unconditionally. */
    public static void reset() {
        clock = Clock.systemUTC();
    }
}
