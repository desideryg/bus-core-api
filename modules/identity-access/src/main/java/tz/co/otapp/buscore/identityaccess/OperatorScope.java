package tz.co.otapp.buscore.identityaccess;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tz.co.otapp.buscore.apicontracts.error.ApiException;

/**
 * Which operators' rows the caller may see and touch.
 *
 * <p>Derived once, server-side, from the authenticated principal. Passed <em>into</em> a service; never
 * read from a request body, because a body is something a caller can edit.
 *
 * <p>It answers <b>whose</b>. {@link PermissionGuard} answers <b>what</b>. Both run on an operator-owned
 * route and neither substitutes for the other: a platform administrator who reaches every operator's
 * vehicles still needs {@code VEHICLE.READ} to read one.
 *
 * <h2>Two shapes, one record</h2>
 *
 * <p>{@link #platform()} means the caller belongs to no tenancy and may therefore be scoped to all of it —
 * every operator, and none of its own. Otherwise {@link #operatorUids()} is exactly the memberships the
 * staff member holds, one <b>or many</b>. Many is legal, and there is no "choose an operator" refusal
 * anywhere in this class: a shared-services employee covering two depots sees both.
 *
 * <h2>The boolean widens a read, not the list — and this is the trap</h2>
 *
 * <p>A platform scope and a broken scope <em>both</em> have an empty uid list. Code that keys off
 * emptiness therefore gets it exactly backwards: the caller with the widest reach looks identical to the
 * caller with none.
 *
 * <p>So emptiness is <b>always</b> restrictive here. {@link #filter()} never returns an empty collection —
 * it returns a sentinel that matches nothing — and the resolver refuses an operator staff member with no
 * memberships rather than handing back an empty scope. The dangerous shape is the natural one:
 *
 * <pre>
 * if (uids.isEmpty()) { /* skip the WHERE clause *&#47; }   // turns "no operators" into "all rows"
 * </pre>
 */
public record OperatorScope(boolean platform, List<UUID> operatorUids) {

    /**
     * A uid no row carries.
     *
     * <p>JPA cannot bind an empty {@code IN} list, and a platform scope has no operators to bind — so
     * {@link #filter()} yields this instead. It is safe precisely because it matches nothing: the
     * {@code :platform = true} disjunct has already opened the query by the time it is evaluated.
     */
    private static final UUID MATCHES_NOTHING = new UUID(0L, 0L);

    public OperatorScope {
        operatorUids = List.copyOf(operatorUids);
    }

    /** Platform staff: every operator's rows, and no operator of their own. */
    public static OperatorScope allOperators() {
        return new OperatorScope(true, List.of());
    }

    /** Operator staff: exactly these operators' rows, one or many. */
    public static OperatorScope restrictedTo(List<UUID> operatorUids) {
        return new OperatorScope(false, operatorUids);
    }

    /**
     * The {@code IN} list to bind beside {@link #platform()}:
     *
     * <pre>
     * &#64;Query("select v from Vehicle v where (:platform = true or v.operatorUid in :operatorUids)")
     * List&lt;Vehicle&gt; findAllInScope(&#64;Param("platform") boolean platform,
     *                              &#64;Param("operatorUids") Collection&lt;UUID&gt; operatorUids);
     *
     * repository.findAllInScope(scope.platform(), scope.filter());
     * </pre>
     *
     * <p><b>Never empty</b>, so the bind always succeeds.
     */
    public Collection<UUID> filter() {
        return platform || operatorUids.isEmpty() ? List.of(MATCHES_NOTHING) : operatorUids;
    }

    /**
     * Whether a row owned by {@code operatorUid} is inside this scope. Use after fetching by uid, where
     * the row is already loaded and no query needed scoping.
     *
     * <p><b>{@code permits(null)} answers {@link #platform()}</b>, and that is load-bearing rather than
     * incidental: a row belonging to no operator is reachable only by platform staff. Null is the
     * restrictive case, not a hole. The explicit null check is also what stops
     * {@code List.of(...).contains(null)} throwing.
     */
    public boolean permits(UUID operatorUid) {
        return platform || (operatorUid != null && operatorUids.contains(operatorUid));
    }

    /**
     * The operator a <b>new</b> row belongs to — the one question a scope alone cannot answer, and so the
     * one place a caller names an operator.
     *
     * <p>It arrives as the {@code ?operator=} <b>query parameter</b> and is validated here against the
     * caller's own scope, so it confers nothing: it selects among operators they already reach and can
     * never widen them. It is not in the body, because authority never is.
     *
     * @param requested the {@code ?operator=} parameter, or null when the caller named none
     * @throws ApiException {@code 403 SCOPE.NOT_AUTHORISED} when the named operator is outside this scope;
     *         {@code 400 SCOPE.OPERATOR_REQUIRED} when none was named and the scope names no single
     *         operator — which is platform staff always, and multi-operator staff who did not choose.
     *         <b>The most privileged caller is the one who must supply the parameter</b>, which looks
     *         backwards and is not: they are the one for whom it is genuinely ambiguous.
     */
    public UUID requireTarget(UUID requested) {
        if (requested != null) {
            if (!permits(requested)) {
                throw new ApiException(ScopeErrors.NOT_AUTHORISED,
                        "That operator is outside your scope.");
            }
            return requested;
        }
        return soleOperator().orElseThrow(() -> new ApiException(ScopeErrors.OPERATOR_REQUIRED,
                "Name the owning operator with the 'operator' query parameter."));
    }

    /** The one operator this scope names, when it names exactly one. */
    private Optional<UUID> soleOperator() {
        return !platform && operatorUids.size() == 1 ? Optional.of(operatorUids.getFirst()) : Optional.empty();
    }
}
