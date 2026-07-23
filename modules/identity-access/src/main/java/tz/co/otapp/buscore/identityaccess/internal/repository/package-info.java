/**
 * Spring Data repositories over this module's entities.
 *
 * <p><b>A lookup must match the index that guarantees it.</b> Where uniqueness is enforced functionally in
 * the schema — a unique index on {@code lower(username)}, say — the repository method must be the
 * case-insensitive one. A case-sensitive query against a case-insensitive index does not fail; it
 * silently bypasses the guarantee at read time, which is how two accounts differing only in case end up
 * both reachable.
 *
 * <p>Queries that return operator-owned rows take the caller's operator scope as parameters and filter on
 * it in SQL, rather than filtering in memory afterwards. Note the shape carefully: it is the platform
 * <em>boolean</em> that widens a read, not the uid list — a platform scope and a broken scope both carry
 * an empty list, so a query that keys off emptiness gets it exactly backwards.
 */
package tz.co.otapp.buscore.identityaccess.internal.repository;
