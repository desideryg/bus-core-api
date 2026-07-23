package tz.co.otapp.buscore.identityaccess;

import org.springframework.stereotype.Component;

/**
 * The one place a permission decision is made, reachable from any module as the SpEL bean {@code @perm}:
 *
 * <pre>
 * &#64;PreAuthorize("&#64;perm.has('" + Permissions.ROLE_GRANT + "')")
 * public ApiResponse&lt;Void&gt; grant(...) { … }
 * </pre>
 *
 * <p>Reached by <b>bean name</b>, so a module gating a route gains no compile-time coupling to how the
 * decision is made. The constant is concatenated rather than written as a literal, so a typo is a compile
 * error rather than a route that silently refuses everyone.
 *
 * <h2>Why a bean, and not a bare hasAuthority()</h2>
 *
 * <p>{@code hasAuthority('ROLE.GRANT')} would work today with no new code, and that is precisely the trap.
 * It scatters every authorisation decision in the system across a hundred expressions with no single point
 * of interpretation — which makes the ROOT bypass below <b>unimplementable</b>, and any future addition
 * (a tenancy condition, an audit hook, a deny-list) a hundred-site rewrite.
 *
 * <h2>The ROOT bypass — the only one in the system</h2>
 *
 * <p>ROOT sits outside RBAC entirely: it holds no role, appears nowhere in the seed, and its permission set
 * is genuinely empty. Its authority is the single branch below.
 *
 * <p>That is why it lives in <b>code</b>, where no migration can revoke it and no future token-minting path
 * can forget it. It is also why {@code hasAuthority(...)} must never appear anywhere in the reactor: ROOT
 * carries no authorities, so the first bare expression merged would lock the break-glass identity out of
 * the very system it exists to rescue, recoverable only by a direct database write.
 *
 * <h2>Why the staff check is explicit</h2>
 *
 * <p>A non-staff principal would fail {@link #has} anyway, since its permission set is always empty.
 * Refusing on that basis would be relying on a <em>coincidence</em> — a fact about a collection that a
 * future change to how principals are built could quietly falsify. The check is stated: a non-staff caller
 * is refused because it is not staff.
 *
 * <h2>It never throws</h2>
 *
 * <p>{@link #has} is a pure predicate returning false, so expressions compose:
 * {@code "@perm.has('A') or @perm.has('B')"}. The refusal itself is raised by Spring's method-security
 * interceptor and rendered as the standard envelope by the response advice.
 */
@Component("perm")
public class PermissionGuard {

    private final PrincipalContext principalContext;

    public PermissionGuard(PrincipalContext principalContext) {
        this.principalContext = principalContext;
    }

    /** True when the caller is staff that is ROOT, or that holds the named {@code DOMAIN.ACTION}. */
    public boolean has(String permission) {
        return principalContext.current().map(principal -> holds(principal, permission)).orElse(false);
    }

    /** True when the caller holds any one of these — for a route two roles legitimately reach. */
    public boolean hasAny(String... permissions) {
        return principalContext.current()
                .map(principal -> {
                    for (String permission : permissions) {
                        if (holds(principal, permission)) {
                            return true;
                        }
                    }
                    return false;
                })
                .orElse(false);
    }

    private static boolean holds(Principal principal, String permission) {
        // Stated, not inferred from an empty collection. See "Why the staff check is explicit".
        if (principal.type() != PrincipalType.STAFF) {
            return false;
        }
        // THE ROOT BYPASS. The only one in the system.
        if (principal.isRoot()) {
            return true;
        }
        return principal.permissions().contains(permission);
    }
}
