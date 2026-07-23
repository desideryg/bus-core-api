package tz.co.otapp.buscore.identityaccess;

import java.util.List;

import org.springframework.stereotype.Component;

import tz.co.otapp.buscore.apicontracts.error.ApiException;

/**
 * Turns the acting principal into the set of operators they may reach.
 *
 * <p>Injected by any module with operator-owned rows:
 *
 * <pre>
 * OperatorScope scope = scopes.require();
 * return vehicles.findAllInScope(scope.platform(), scope.filter());
 * </pre>
 *
 * <h2>Why this exists once</h2>
 *
 * <p>Because the question is asked in a dozen modules, and a dozen implementations disagree. In the
 * reference implementation five modules each grew their own version, and <b>four of them refused an empty
 * uid list</b> — so a platform administrator holding every permission in the system was refused 403 on
 * every operator-owned endpoint, and the permission layer was decorative on those routes. All five also
 * refused a list of more than one, so nobody could serve two operators.
 *
 * <p>One resolver means one answer, and one place to change it.
 *
 * <h2>The resolution order is itself the invariant</h2>
 *
 * <ol>
 *   <li>Not staff → refused. An agent has no tenancy and never will.</li>
 *   <li>{@code ROOT} or {@code ADMIN} → every operator. They belong to no tenancy, so they are scoped to
 *       all of it.</li>
 *   <li>{@code PARTNER} → <b>refused, fail-closed</b>. Granting a partner platform scope would hand one
 *       organisation every operator's data; granting operator scope is impossible, since a partner has no
 *       memberships. Neither answer is safe, so there is no answer.</li>
 *   <li>{@code OPERATOR} with no memberships → refused. Serving nobody means seeing nothing.</li>
 *   <li>Otherwise → exactly their memberships, however many.</li>
 * </ol>
 *
 * <p>Note that steps 2 and 4 are why {@code userType} is consulted <em>before</em> the uid list is ever
 * read: platform staff never reach the emptiness branch, so an empty list can be treated as restrictive
 * everywhere without stranding the callers who legitimately have one.
 */
@Component
public class OperatorScopeResolver {

    private final PrincipalContext principalContext;

    public OperatorScopeResolver(PrincipalContext principalContext) {
        this.principalContext = principalContext;
    }

    /**
     * The acting caller's scope.
     *
     * @throws ApiException {@code 401} when unauthenticated; {@code 403 SCOPE.NOT_AUTHORISED} when the
     *         caller has no scope that can safely be resolved
     */
    public OperatorScope require() {
        Principal principal = principalContext.require();

        if (principal.type() != PrincipalType.STAFF) {
            // An agent's authority comes from selling grants, not from a tenancy. Asking this question of
            // one is a bug at the call site, not a permission problem.
            throw new ApiException(ScopeErrors.NOT_AUTHORISED);
        }

        return switch (principal.tenancy()) {
            case ROOT, ADMIN -> OperatorScope.allOperators();
            case PARTNER -> throw new ApiException(ScopeErrors.NOT_AUTHORISED,
                    "Partner accounts have no operator scope.");
            case OPERATOR -> operatorScopeOf(principal);
            case null -> throw new ApiException(ScopeErrors.NOT_AUTHORISED);
        };
    }

    private static OperatorScope operatorScopeOf(Principal principal) {
        if (principal.operatorUids().isEmpty()) {
            // Staff bound to no operator get NOTHING rather than everything. The opposite reading is one
            // isEmpty() check away and is unrestricted access.
            throw new ApiException(ScopeErrors.NOT_AUTHORISED,
                    "This account is not linked to any operator.");
        }
        return OperatorScope.restrictedTo(List.copyOf(principal.operatorUids()));
    }
}
