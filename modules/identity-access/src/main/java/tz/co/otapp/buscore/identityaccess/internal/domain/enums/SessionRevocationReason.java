package tz.co.otapp.buscore.identityaccess.internal.domain.enums;

import tz.co.otapp.buscore.apicontracts.enums.DescribedEnum;

/**
 * Why a session was ended before it expired.
 *
 * <p>Stored on the session row so the trail can answer "what stopped this session" without joining to
 * anything. Kept coarse for the reason {@link AuthEventType} is — a vocabulary nobody can enumerate is a
 * vocabulary nobody queries — and every value here is an action somebody or something deliberately took,
 * never the ordinary passage of a session past its expiry, which needs no reason because it is not a
 * revocation.
 *
 * <p>Described like every other enum in the system, so an admin surface that lists sessions can render it —
 * but <b>never returned to the caller whose session ended</b>. A holder of a spent token learns only that
 * the session is over (see {@code REFRESH_TOKEN_INVALID}), never that the cause was {@link
 * #REFRESH_TOKEN_REUSED}, which would confirm a theft succeeded.
 */
public enum SessionRevocationReason implements DescribedEnum {

    /** The holder signed out. */
    LOGOUT("Signed out", "The holder ended the session themselves."),

    /** The account was suspended or blocked, so every session it held had to stop with it. */
    ACCOUNT_WITHDRAWN("Account withdrawn",
            "The account was suspended or blocked, so the sessions it held stopped with it."),

    /** The holder changed their password; other sessions must not outlive the credential that opened them. */
    PASSWORD_CHANGED("Password changed",
            "The holder changed their password, so sessions opened under the old one were ended."),

    /**
     * A password was set through a reset token — a recovery.
     *
     * <p>The one that most needs to revoke: a forgotten-password recovery is exactly the moment an account
     * may have been taken over, and a session left alive is the attacker's, not the holder's.
     */
    CREDENTIAL_RESET("Credential reset",
            "A password was set through a reset token, so any existing session was ended."),

    /**
     * A refresh token that had already been rotated away was presented again.
     *
     * <p>Treated as a stolen credential rather than a client bug: the whole session is revoked so that
     * whichever party still holds a live token — attacker or holder — is put back to a fresh sign-in.
     */
    REFRESH_TOKEN_REUSED("Refresh token reused",
            "A spent refresh token was presented again, so the session was revoked as compromised.");

    private final String name;
    private final String description;

    SessionRevocationReason(String name, String description) {
        this.name = name;
        this.description = description;
    }

    @Override
    public String getValue() {
        return name();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }
}
