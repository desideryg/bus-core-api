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

    /**
     * A staff member gained reach over another operator.
     *
     * <p>Recorded because it is a widening of access that no role grant would show: two people holding
     * identical roles can see entirely different rows, and this is the only record of why.
     */
    OPERATOR_LINKED("Operator linked", "A staff account was attached to an operator."),

    /** A staff member lost reach over an operator. */
    OPERATOR_UNLINKED("Operator unlinked", "A staff account was detached from an operator.");

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
