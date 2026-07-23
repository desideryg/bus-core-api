package tz.co.otapp.buscore.identityaccess.internal.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Minting and fingerprinting one-time reset tokens.
 *
 * <p>Two operations that must agree exactly, kept in one place so they cannot drift: a token is generated
 * here and only ever recognised by comparing {@link #fingerprint} of the presented value against the stored
 * one.
 */
public final class ResetTokens {

    /**
     * 32 bytes — 256 bits of entropy.
     *
     * <p>This is what makes a fast hash acceptable for storage and makes guessing pointless: an attacker
     * submitting tokens at any achievable rate is not meaningfully closer to a hit after a century. The
     * corresponding weakness of a short, human-typable code is exactly why one is not used.
     */
    private static final int TOKEN_BYTES = 32;

    /**
     * Seeded from the operating system and never re-seeded by us.
     *
     * <p>{@code new SecureRandom()} rather than {@code SecureRandom.getInstanceStrong()}: the latter can
     * block indefinitely on a machine short of entropy, which turns a password reset into a hung request.
     * The default is cryptographically secure and does not block.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private ResetTokens() {
    }

    /**
     * A fresh token, in the form the holder receives it.
     *
     * <p>URL-safe and unpadded, so it survives being pasted into a link, a query string or a chat message
     * without re-encoding — a token that arrives with {@code +} turned into a space is a support ticket
     * whose cause is invisible.
     */
    public static String mint() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * The value stored and compared against — hex of SHA-256, always 64 characters.
     *
     * <p>Deterministic by necessity: a redemption presents a token and nothing else, so the row has to be
     * found by it. See the migration for why that does not weaken anything here.
     */
    public static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every JVM. Wrapped rather than declared, so no caller writes a catch
            // block for a condition that cannot arise.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
