package tz.co.otapp.buscore.shared.abstraction;

import java.security.SecureRandom;
import java.util.UUID;

import tz.co.otapp.buscore.shared.time.Times;

/**
 * Mints the {@code uid} values that cross module boundaries — UUID version 7, per RFC 9562.
 *
 * <h2>Why version 7 and not version 4</h2>
 *
 * <p>A v7 uuid begins with a 48-bit millisecond timestamp, so values generated near each other in time sort
 * near each other. That matters because every {@code uid} column carries a unique index: with random v4
 * values each insert lands in a random leaf of the B-tree, so the index cannot stay dense and the working
 * set is effectively the whole index. With v7 the inserts append, the index stays compact, and only the
 * right-hand edge needs to be hot.
 *
 * <p>The cost is that a uid leaks its creation time to anyone holding it. That is accepted: these
 * identifiers appear in URLs a caller already fetched, so the timestamp tells them roughly when a row they
 * are already authorised to see was created.
 *
 * <h2>Why Java and not the database</h2>
 *
 * <p>Postgres 18 can generate these itself, and letting it do so means a row inserted by a migration or a
 * data fix also gets a well-formed uid. We generate in Java anyway, for one reason: <b>a database-generated
 * uid does not exist until the row is flushed</b>. Code that needs the identifier — to sign it into a
 * token, to return it, to reference it in the same transaction — must remember to flush first, and the
 * failure when it forgets is a null in a place nothing checks.
 *
 * <p>Generating here means the uid exists the moment the entity does. That in turn is what lets
 * {@code BaseEntity} use it in {@code equals} and {@code hashCode} safely, which a database-generated value
 * cannot support at all.
 *
 * <p>The consequence to accept: <b>a row inserted by raw SQL must supply its own uid.</b> Use
 * {@code gen_random_uuid()} for seed data — such rows are few, and losing time-ordering on them costs
 * nothing.
 */
public final class Uuids {

    /**
     * A single shared instance. {@link SecureRandom} is thread-safe, and seeding one per call would be
     * both slower and no more random.
     *
     * <p>Secure rather than {@code ThreadLocalRandom} because a uid is a public handle that appears in
     * URLs. Authorisation is enforced independently, so guessing one is not by itself a way in — but a
     * predictable identifier makes enumeration cheap, and the throughput difference here is irrelevant
     * beside a database round trip.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final long VERSION_7 = 0x7000L;
    private static final long VARIANT_RFC_4122 = 0x8000000000000000L;
    private static final long CLEAR_TOP_TWO_BITS = 0x3FFFFFFFFFFFFFFFL;
    private static final long FORTY_EIGHT_BIT_MASK = 0xFFFFFFFFFFFFL;
    private static final int TWELVE_BIT_BOUND = 0x1000;

    private Uuids() {
    }

    /**
     * A new time-ordered identifier.
     *
     * <p>Layout, most significant bit first: 48 bits of Unix milliseconds, 4 bits of version, 12 random
     * bits, 2 bits of variant, 62 random bits.
     *
     * <p>Values minted within the same millisecond are <b>not</b> ordered relative to one another — the
     * 12 random bits are left random rather than used as a counter. RFC 9562 permits both, and ordering
     * within a millisecond buys nothing here: the index locality that motivates v7 comes from the
     * millisecond prefix, which those values already share.
     */
    public static UUID next() {
        long millis = Times.now().toEpochMilli();

        long mostSignificant = (millis & FORTY_EIGHT_BIT_MASK) << 16
                | VERSION_7
                | RANDOM.nextInt(TWELVE_BIT_BOUND);

        long leastSignificant = RANDOM.nextLong() & CLEAR_TOP_TWO_BITS | VARIANT_RFC_4122;

        return new UUID(mostSignificant, leastSignificant);
    }
}
