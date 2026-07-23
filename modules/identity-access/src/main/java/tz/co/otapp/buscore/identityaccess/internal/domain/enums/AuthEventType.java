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
    ROLE_REVOKED("Role revoked", "A role was removed from a staff account.");

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
