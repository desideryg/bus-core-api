package tz.co.otapp.buscore.identityaccess;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated actor, as every other module sees it.
 *
 * <p><b>Authority is read from here and never from a request body.</b> Whose rows a caller may touch, what
 * they may do, and which operator they act for are all derived from the authenticated token — a field on a
 * request body that names who the caller is, is a field a caller can forge.
 *
 * <p><b>It carries no {@code Long} id.</b> The module-private primary key does not cross this boundary;
 * {@link #uid} is the handle, and it is the only one.
 *
 * <h2>Everything derived here is a snapshot</h2>
 *
 * <p>Permissions and operator memberships are resolved at sign-in and carried in the token, so both are
 * <b>correct at issue and increasingly stale for the whole of the token's life</b>. A role revoked, or an
 * operator unlinked, one minute after sign-in stays effective until the token expires.
 *
 * <p>That is the trade for not querying the database on every request, and it is why the lifetime is
 * short. Where a change must take effect immediately, the lever is revoking the session — a later slice —
 * not revoking the grant.
 *
 * @param uid          the actor's public handle
 * @param type         which kind of actor the uid refers to
 * @param tenancy      which organisation a staff actor belongs to. <b>Null for any non-staff principal</b>;
 *                     an agent has no tenancy, and inventing one would invite a role grant
 * @param operatorUids the operators a staff member serves — <b>one or many</b>. Empty for platform staff,
 *                     who belong to no tenancy and are scoped to all of it, and empty for a non-staff
 *                     principal. <b>Empty never means "all"</b>: see {@link OperatorScope}
 * @param permissions  the {@code DOMAIN.ACTION} codes held, flattened from every live role. <b>Always
 *                     empty for ROOT</b>, which holds no roles at all — its authority is a single branch
 *                     in the guard, not a set of grants
 */
public record Principal(
        UUID uid,
        PrincipalType type,
        StaffTenancy tenancy,
        List<UUID> operatorUids,
        Set<String> permissions) {

    public Principal {
        // Never null, so a caller can test membership without a null check and a malformed token cannot
        // produce a principal whose collections explode on first use.
        operatorUids = operatorUids == null ? List.of() : List.copyOf(operatorUids);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);

        // AN AGENT HOLDS NOTHING, AND THAT IS ENFORCED RATHER THAN DOCUMENTED.
        //
        // Agents are authorised by selling grants in the `agent` module — four waves later, and invisible
        // from here. This module cannot resolve an agent's authority, so it must never assert any.
        //
        // Until now that was true only by accident: nothing built an agent principal with permissions, so
        // every gated route refused them. Audience's own javadoc calls that out — a fact about an empty
        // collection rather than a decision anybody made, which stops being true the moment something
        // populates the set.
        //
        // Checking it here makes it a decision. The payoff is at the token boundary: JwtService#parse
        // catches IllegalArgumentException and returns empty, so a FORGED TOKEN claiming AGENT with a
        // populated permissions claim is not a privileged principal — it is an unusable token, refused the
        // same way a bad signature is.
        if (type == PrincipalType.AGENT && (!permissions.isEmpty() || tenancy != null
                || !operatorUids.isEmpty())) {
            throw new IllegalArgumentException(
                    "An agent principal carries no permissions, tenancy or operators.");
        }
    }

    /**
     * The break-glass identity.
     *
     * <p>Asked here rather than compared at each call site, so the one place that grants ROOT its bypass is
     * findable by searching for this method.
     */
    public boolean isRoot() {
        return tenancy == StaffTenancy.ROOT;
    }
}
