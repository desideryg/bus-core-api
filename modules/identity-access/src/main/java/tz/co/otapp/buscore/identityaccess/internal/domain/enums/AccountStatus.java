package tz.co.otapp.buscore.identityaccess.internal.domain.enums;

import tz.co.otapp.buscore.apicontracts.enums.DescribedEnum;

/**
 * Whether an account may be used.
 *
 * <p>Module-private for now. It will be promoted to {@code api-contracts} the day a second module needs
 * it — most likely when an admin surface has to render a staff list — and not before: a type used by one
 * module and placed in the artifact everything depends on turns a one-module change into a full rebuild.
 *
 * <h2>Every non-active state is indistinguishable to a caller</h2>
 *
 * <p>These values shape internal decisions, but a failed login reports the <em>same</em> refusal for all of
 * them, and the same one it reports for an unknown username or a wrong password. Telling a caller that an
 * account is suspended confirms it exists — the single most useful thing an attacker learns from a login
 * form, and it is free if the refusals differ.
 *
 * <p>The descriptions below are written for the staff administering accounts, who legitimately see them.
 * They are never returned to a caller who failed to log in.
 */
public enum AccountStatus implements DescribedEnum {

    /** Created, but the person has not yet set a password. Cannot log in. */
    PENDING("Pending",
            "The account exists but no password has been set yet. The holder cannot sign in until they "
                    + "complete setup."),

    /** Usable. The only status that permits a login. */
    ACTIVE("Active",
            "The account is in normal use and may sign in."),

    /**
     * Temporarily withdrawn — leave, an investigation, a role change in progress. Reversible.
     */
    SUSPENDED("Suspended",
            "Access is withdrawn for now and can be restored. Use for leave, an investigation, or any "
                    + "situation where the person is expected back."),

    /**
     * Permanently withdrawn.
     *
     * <p>Distinct from {@link #SUSPENDED} because the two answer different questions: suspension asks
     * "should this person be working today", blocking asks "should this account ever be used again". One
     * state for both would force whoever came to reverse it to guess which had been meant.
     */
    BLOCKED("Blocked",
            "Access is withdrawn permanently. Use when the person has left or the account must never be "
                    + "used again; prefer Suspended if there is any chance of return.");

    private final String name;
    private final String description;

    AccountStatus(String name, String description) {
        this.name = name;
        this.description = description;
    }

    /** The stable code. Identical to the constant name, which is what persistence writes. */
    @Override
    public String getValue() {
        return name();
    }

    /** The display label — {@code Active}, not {@code ACTIVE}. Copy; never branch on it. */
    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }
}
