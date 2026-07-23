package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

/**
 * What counts as an acceptable password, in one place.
 *
 * <p>Here rather than repeated across the request records so the three paths that set a password — a
 * change, a redemption, and the bootstrap — cannot disagree. A rule enforced on two of three paths is not
 * a rule; it is a suggestion with a gap.
 *
 * <h2>Length, and deliberately nothing else</h2>
 *
 * <p>No "one uppercase, one digit, one symbol". Composition rules push people towards {@code Password1!}
 * and its neighbours — a small, well-known space — while forbidding long passphrases that are genuinely
 * stronger. Current guidance (NIST SP 800-63B) is explicit that length is the property worth requiring and
 * that composition rules should not be imposed.
 *
 * <p>The one substantive rule beyond length is that a new password must differ from the current one, and
 * that cannot be expressed as an annotation because it needs the stored hash. It lives in the service.
 */
public final class PasswordPolicy {

    /**
     * Long enough that a passphrase is the natural way to satisfy it.
     *
     * <p>These are staff accounts reachable from the internet, and the lockout only slows online guessing —
     * it does nothing about a stolen hash. Twelve is the point where a memorable phrase beats a mangled
     * word.
     */
    public static final int MIN_LENGTH = 12;

    /**
     * An upper bound, because a hash function should never be handed unbounded input from a caller.
     *
     * <p>Generous enough that no real passphrase meets it.
     */
    public static final int MAX_LENGTH = 128;

    static final String MESSAGE = "A password must be at least " + MIN_LENGTH + " characters.";

    private PasswordPolicy() {
    }
}
