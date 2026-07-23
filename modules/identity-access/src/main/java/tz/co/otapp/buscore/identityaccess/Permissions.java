package tz.co.otapp.buscore.identityaccess;

import java.util.Set;

/**
 * Every permission code in the system, as constants.
 *
 * <p>A route names one of these; a caller either holds it or is refused. The <b>string</b> is the contract
 * — it is stored in the database, baked into tokens, and searched for by whoever is working out why
 * somebody cannot do something. These constants exist so no route spells one by hand.
 *
 * <h2>Published here rather than in api-contracts</h2>
 *
 * <p>The usual argument for putting permission codes in the contracts module is that gating a route should
 * not require depending on identity-access. In this system it already does: every module that gates a
 * route also resolves the acting principal, so the dependency exists regardless.
 *
 * <p>What that placement would cost is real, though. These constants must agree exactly with the SQL seed
 * that inserts them, and the seed lives in this module. Keeping both here makes the coupling visible and
 * lets one test hold them to each other.
 *
 * <h2>Declared and seeded, or the code is inert</h2>
 *
 * <p><b>A code named by a route but missing from the seed refuses everyone, forever, and silently.</b> The
 * permission simply does not exist to be granted, so no role can hold it and every caller fails the check
 * — including callers who by every other measure should pass. Nothing logs an error; the route just never
 * works for anybody.
 *
 * <p>It is also invisible to an integration test, because a test runs either as a fully-granted
 * administrator (who is granted every <em>seeded</em> code, and so not this one) or as ROOT (who bypasses
 * the check entirely). {@code PermissionCatalogTest} compares this list against the seeded rows in both
 * directions, and it is the only thing that catches it.
 *
 * <h2>Naming</h2>
 *
 * <p>{@code DOMAIN.ACTION}, uppercase, singular domain. The domain is the thing acted upon, not the module
 * that happens to own it.
 *
 * <p><b>Never grant by pattern.</b> A role defined as "everything matching {@code %.READ}" turns a rename
 * into a privilege escalation: a code renamed to end in {@code .READ} silently joins the granted set of
 * every role using that pattern. Grants list their codes explicitly, in the seed.
 */
public final class Permissions {

    private Permissions() {
    }

    // ─────────────────────────────── roles ───────────────────────────────

    /** See which roles exist and what each confers. */
    public static final String ROLE_READ = "ROLE.READ";

    /** Give a staff member a role. */
    public static final String ROLE_GRANT = "ROLE.GRANT";

    /**
     * Take a role away from a staff member.
     *
     * <p>Separate from {@link #ROLE_GRANT} because they are different powers: granting widens access and
     * revoking narrows it, and there are people who should be able to do one and not the other during an
     * incident.
     */
    public static final String ROLE_REVOKE = "ROLE.REVOKE";

    // ──────────────────────────── permissions ────────────────────────────

    /** See the catalog of permissions, in order to compose a role from it. */
    public static final String PERMISSION_READ = "PERMISSION.READ";

    // ─────────────────────────────── staff ───────────────────────────────

    /** See staff accounts other than one's own. */
    public static final String STAFF_READ = "STAFF.READ";

    /**
     * The whole catalog, for the test that holds it against the database.
     *
     * <p>Maintained by hand alongside the constants above. That duplication is deliberate: it is what makes
     * "somebody added a constant and forgot the seed" a build failure rather than a route nobody can use.
     */
    public static final Set<String> ALL = Set.of(
            ROLE_READ,
            ROLE_GRANT,
            ROLE_REVOKE,
            PERMISSION_READ,
            STAFF_READ);
}
