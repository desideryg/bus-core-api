package tz.co.otapp.buscore.identityaccess.internal.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hashing a PIN, which is not the same problem as hashing a password.
 *
 * <h2>The problem</h2>
 *
 * <p>An adaptive hash defends a password because the candidate space is enormous: an attacker who steals
 * the hashes still has to guess, and the work factor makes each guess expensive. A six-digit PIN has a
 * million candidates. At any realistic bcrypt cost an attacker with the {@code pin_hash} column walks the
 * <em>entire</em> space for every account, offline, in a weekend. Raising the work factor does not fix it;
 * it only decides how long the weekend is.
 *
 * <p>So for a PIN the stolen-hash case is not a slow attack. It is a solved one.
 *
 * <h2>The fix: a key the database does not contain</h2>
 *
 * <p>The PIN is HMAC'd with a server-side secret — a <b>pepper</b> — before it is hashed. The pepper lives
 * in configuration, not in the database, so the {@code pin_hash} column on its own is no longer enough to
 * test a candidate. An attacker now needs the database <em>and</em> the application's configuration, which
 * are two different compromises rather than one.
 *
 * <p>It is not a substitute for the lockout, which is about guessing <em>online</em>, and it is not a
 * substitute for the rate limiting of slice 8. It closes a different door.
 *
 * <h2>Both directions live here</h2>
 *
 * <p>Encoding and verifying are one class on purpose. They must apply the identical transformation, and a
 * PIN-setting surface that peppered differently — or not at all — would produce credentials that never
 * verify, with nothing to indicate why. There is one place to get it right.
 *
 * <h2>The cost, stated</h2>
 *
 * <p>Rotating the pepper invalidates every PIN, because a peppered hash cannot be re-peppered without the
 * original. That is the trade, and it is the same trade every peppered scheme makes. The remedy is the same
 * as for a forgotten PIN — re-issue — which is why rotating it is an operational decision rather than a
 * routine one.
 */
@Component
public class PinEncoder {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * The delegating encoder the rest of the system uses, so a stored PIN hash carries the same
     * {@code {bcrypt}} prefix and can be upgraded by the same mechanism as everything else.
     */
    private final PasswordEncoder delegate;

    private final SecretKeySpec pepper;

    public PinEncoder(PasswordEncoder delegate,
            @Value("${identity.agent.pin-pepper}") String pepper) {
        this.delegate = delegate;

        // FAILS AT STARTUP, not at the first sign-in, and not silently with a weak key. The same rule the
        // JWT secret follows: a deployment mistake should surface while whoever deployed it is watching,
        // rather than becoming a property of the system nobody notices.
        if (pepper == null || pepper.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "identity.agent.pin-pepper must be set and at least 32 bytes. Without it the PIN hashes "
                            + "are exhaustible offline by anyone who reads the credentials table.");
        }
        this.pepper = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /** The value to store. */
    public String encode(String rawPin) {
        return delegate.encode(peppered(rawPin));
    }

    /**
     * Whether a presented PIN matches a stored hash.
     *
     * <p>Delegates the comparison itself, so the constant-time behaviour of the underlying encoder is
     * preserved — a comparison written here would be the obvious place to accidentally introduce a
     * short-circuit that leaks how much of the value was right.
     */
    public boolean matches(String rawPin, String storedHash) {
        return delegate.matches(peppered(rawPin), storedHash);
    }

    /**
     * HMAC rather than plain concatenation.
     *
     * <p>{@code hash(pepper + pin)} is the tempting shorthand and it is weaker: it exposes the construction
     * to length-extension, and it makes the pepper's length a property an attacker can probe. HMAC is the
     * standard construction for keying a hash and costs nothing extra here.
     *
     * <p>Base64 rather than raw bytes, because the delegate takes a {@code CharSequence} and encoders
     * differ in how they treat a byte that is not valid text.
     */
    private String peppered(String rawPin) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(pepper);
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(rawPin.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            // HmacSHA256 is required of every JVM, and the key was validated in the constructor. Wrapped
            // rather than declared so no caller writes a catch block for a condition that cannot arise.
            throw new IllegalStateException("HMAC-SHA256 is unavailable", impossible);
        }
    }
}
