package tz.co.otapp.buscore.identityaccess.internal.domain.enums;

import tz.co.otapp.buscore.apicontracts.enums.DescribedEnum;

/**
 * What happened, on the authentication path.
 *
 * <p>Kept deliberately coarse. A trail with fifty event types is one nobody can query, because answering
 * "did anything go wrong for this account" means knowing which forty of them count as wrong. These are the
 * distinctions somebody actually asks about during an incident.
 */
public enum AuthEventType implements DescribedEnum {

    /** Credentials accepted and a token issued. */
    LOGIN_SUCCESS("Sign-in succeeded", "Credentials were accepted and a token was issued."),

    /**
     * Credentials rejected.
     *
     * <p>One type covering an unknown identifier, a wrong password and an unusable account — the same
     * three the caller cannot distinguish. The <em>detail</em> is in the identifier and address columns,
     * which is where an investigation looks anyway.
     */
    LOGIN_FAILURE("Sign-in failed", "Credentials were rejected, or the account cannot sign in."),

    /** The account was refused because it is locked, regardless of what was presented. */
    LOGIN_BLOCKED_BY_LOCKOUT("Sign-in blocked", "The account was locked when the attempt was made."),

    /** Consecutive failures reached the threshold and the account was locked. */
    ACCOUNT_LOCKED("Account locked", "Consecutive failures reached the threshold and the account was locked."),

    /** A correct password on an account that must rotate it. No token was issued. */
    PASSWORD_CHANGE_REQUIRED("Password change required",
            "The password was correct but must be changed before the account can be used."),

    /** A role was given to somebody. */
    ROLE_GRANTED("Role granted", "A role was added to a staff account."),

    /** A role was taken away. */
    ROLE_REVOKED("Role revoked", "A role was removed from a staff account."),

    /** An account came into existence. The first entry in its history, and the one that names its creator. */
    STAFF_CREATED("Staff account created", "A staff login identity was provisioned."),

    /**
     * Access was withdrawn, whether for now or for good.
     *
     * <p>One event for both, with the resulting status recorded alongside: the question afterwards is
     * "when did this account stop working, and who stopped it", and splitting it in two would mean asking
     * it twice.
     */
    STAFF_SUSPENDED("Staff access withdrawn", "A staff account was suspended or blocked."),

    /** Access was returned. */
    STAFF_RESTORED("Staff access restored", "A withdrawn staff account was returned to use."),

    /** An account's editable details — its display name — were changed. */
    STAFF_UPDATED("Staff account updated", "A staff account's display name was changed."),

    /**
     * A staff member gained reach over another operator.
     *
     * <p>Recorded because it is a widening of access that no role grant would show: two people holding
     * identical roles can see entirely different rows, and this is the only record of why.
     */
    OPERATOR_LINKED("Operator linked", "A staff account was attached to an operator."),

    /** A staff member lost reach over an operator. */
    OPERATOR_UNLINKED("Operator unlinked", "A staff account was detached from an operator."),

    /** The holder set a new password by presenting the current one. */
    PASSWORD_CHANGED("Password changed", "An account's password was changed by its holder."),

    /**
     * An administrator created a one-time token for setting an account's password.
     *
     * <p>The single most sensitive administrative act in the module: it is the power to obtain a credential
     * for somebody else's account. Recorded with the issuer, because a takeover investigation starts by
     * asking who caused the reset to exist.
     */
    PASSWORD_RESET_ISSUED("Password reset issued", "A one-time password-reset token was created."),

    /** A reset token was spent and a password set. Pairs with the issue event to close the loop. */
    PASSWORD_RESET_REDEEMED("Password reset redeemed", "A password was set using a one-time token."),

    /**
     * A reset token was presented and refused — unknown, expired or already spent.
     *
     * <p>Recorded because a run of them is the signature of somebody guessing tokens, and the refusal is
     * deliberately identical for all three causes, so this row is the only place the distinction survives.
     */
    PASSWORD_RESET_REJECTED("Password reset rejected", "A reset token was presented and was not usable."),

    // ─────────────────────────────── the session lifecycle ───────────────────────────────

    /** A refresh token was rotated for a new access token, extending a session without a fresh sign-in. */
    TOKEN_REFRESHED("Session extended", "A refresh token was rotated and a new access token was issued."),

    /** A holder ended their own session. Pairs with the sign-in that opened it. */
    LOGGED_OUT("Signed out", "A session was ended by its holder."),

    /**
     * A session was ended by something other than its holder — a withdrawal, a password change, a recovery.
     *
     * <p>One event for all of them, with the reason on the row, for the same purpose {@link #STAFF_SUSPENDED}
     * keeps one event for suspend and block: the question afterwards is "when did this session stop working,
     * and what stopped it", and splitting it per cause would mean asking it several times.
     */
    SESSION_REVOKED("Session revoked", "A session was ended by the system rather than by its holder."),

    /**
     * A refresh token that had already been rotated away was presented again.
     *
     * <p>Its own event, and the reason mirrors {@link #ACCOUNT_LOCKED} standing apart from {@link
     * #LOGIN_FAILURE}: a replayed refresh token is the signature of a stolen one, and the response — revoking
     * the entire session it belonged to — is a security action an investigation asks about directly. Folding
     * it into {@link #SESSION_REVOKED} would bury the one event that says a credential leaked.
     */
    REFRESH_TOKEN_REUSED("Refresh token reused",
            "A spent refresh token was presented again and its session was revoked.");

    private final String name;
    private final String description;

    AuthEventType(String name, String description) {
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
