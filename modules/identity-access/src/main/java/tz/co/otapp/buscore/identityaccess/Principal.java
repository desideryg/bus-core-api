package tz.co.otapp.buscore.identityaccess;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated actor, as every other module sees it.
 *
 * <p><b>Authority is read from here and never from a request body.</b> Whose rows a caller may touch, what
 * they may do, and which agent or operator they act for are all derived from the authenticated token — a
 * field on a request body that names who the caller is, is a field a caller can forge.
 *
 * <p><b>It carries no {@code Long} id.</b> The module-private primary key does not cross this boundary;
 * {@link #uid} is the handle, and it is the only one.
 *
 * <h2>The permissions are a snapshot</h2>
 *
 * <p>They are resolved at sign-in and carried in the token, which means they are <b>correct at issue and
 * increasingly stale for the whole of the token's life</b>. A role revoked one minute after sign-in stays
 * effective until the token expires.
 *
 * <p>That is the trade for not querying the database on every request, and it is why the token lifetime is
 * short. Where an access change must take effect immediately, the lever is revoking the session — a later
 * slice — not revoking the role.
 *
 * @param uid         the actor's public handle
 * @param type        which kind of actor the uid refers to
 * @param tenancy     which organisation a staff actor belongs to. <b>Null for any non-staff principal</b>;
 *                    an agent has no tenancy, and inventing one would invite a role grant
 * @param permissions the {@code DOMAIN.ACTION} codes held, flattened from every role. <b>Always empty for
 *                    ROOT</b>, which holds no roles at all — its authority is a single branch in the guard,
 *                    not a set of grants
 */
public record Principal(UUID uid, PrincipalType type, StaffTenancy tenancy, Set<String> permissions) {

    public Principal {
        // Never null, so a caller can test membership without a null check and a malformed token cannot
        // produce a principal whose permission set explodes on first use.
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
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
