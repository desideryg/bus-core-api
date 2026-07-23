package tz.co.otapp.buscore.identityaccess.internal.domain.enums;

import tz.co.otapp.buscore.apicontracts.enums.DescribedEnum;

/**
 * Which organisation a staff account belongs to.
 *
 * <p>The four values form a <b>partition, not a list</b>: every staff account belongs to exactly one
 * tenancy, and there are only three of those — the platform itself, one operator tree, or a partner
 * organisation. {@link #ROOT} sits on a different axis, as the bootstrap identity that must exist before
 * any role can be granted, because granting a role requires a granter.
 *
 * <h2>This is a tenancy, not a level and not a job</h2>
 *
 * <p><b>Not a level in the operator tree.</b> Staff at an operator and staff at one of its branches are
 * both {@link #OPERATOR}. Naming the level here would mean storing it twice — once as this value and once
 * as the node the account is actually attached to — and the two would eventually disagree.
 *
 * <p><b>Not a job function.</b> Account management and customer support are {@link #ADMIN}s <em>holding a
 * role</em>. The distinction has teeth: a value here can never be granted or revoked, because it is fixed
 * at provisioning. Promoting a supporter to an administrator by changing it would mean issuing a new
 * account and abandoning the old one's audit lineage.
 *
 * <p><b>No value for agents.</b> An agent is not a kind of staff — different table, different credential,
 * and authority that comes from selling grants rather than roles. A value here would make it possible to
 * grant an agent a role.
 *
 * <h2>Persistence</h2>
 *
 * <p>Stored by name. The constant name <em>is</em> the stable code, so a constant may be appended but never
 * renamed or reordered. The label and description are copy and may be reworded at will.
 */
public enum StaffTenancy implements DescribedEnum {

    /**
     * The bootstrap identity. Exactly one ever exists, and the database refuses a second.
     *
     * <p>Break-glass, not a workstation: if it does daily work it becomes a shared account and the audit
     * trail stops naming a human. It exists to grant the first role, after which day-to-day work belongs
     * to an administrator.
     */
    ROOT("Root",
            "Break-glass bootstrap identity. Exactly one exists, and it is created at startup rather than "
                    + "through any API. Its purpose is to grant the first role; a login by it should be "
                    + "rare enough to alert on."),

    /**
     * Platform staff. Belongs to no tenancy, and may therefore be scoped to any.
     *
     * <p>This is the actor that configures fares, commission rules and settlement destinations — which is
     * why staff authenticate with a password rather than the short PIN an agent will use.
     */
    ADMIN("Administrator",
            "Platform staff, belonging to no single operator. Configures fares, commission rules and "
                    + "settlement destinations. Account management and customer support are roles held by "
                    + "an administrator, not tenancies of their own."),

    /**
     * A person at a bus company, at any level of it.
     *
     * <p>The company itself is never a login identity: an operator is an organisation record, and its
     * people each hold their own account.
     */
    OPERATOR("Operator User",
            "A person at a bus company, at the operator or one of its branches. This value names the "
                    + "tenancy, not the level; which node they sit at is resolved elsewhere."),

    /**
     * A human at a partner organisation, using the partner portal.
     *
     * <p>Distinct from that partner's machine client, which will authenticate per request with a key and
     * hold no session. One organisation, two principals, two security models.
     */
    PARTNER("Partner User",
            "A human at a partner organisation, using the partner portal. Distinct from that partner's "
                    + "machine client, which authenticates per request and never holds a session.");

    private final String name;
    private final String description;

    StaffTenancy(String name, String description) {
        this.name = name;
        this.description = description;
    }

    /** The stable code. Identical to the constant name, which is what persistence writes. */
    @Override
    public String getValue() {
        return name();
    }

    /**
     * The display label.
     *
     * <p>Note this is <b>not</b> {@link #name()}: that returns {@code OPERATOR}, this returns
     * {@code Operator User}. Never persist or branch on this one — see {@link DescribedEnum}.
     *
     * <p>The {@code User} suffix on the tenanted labels is load-bearing: it keeps the person
     * ({@code OPERATOR}) distinguishable from the company wherever a human reads both.
     */
    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }
}
