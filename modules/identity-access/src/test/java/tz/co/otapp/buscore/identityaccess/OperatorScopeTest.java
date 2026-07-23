package tz.co.otapp.buscore.identityaccess;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tz.co.otapp.buscore.apicontracts.error.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whose rows a caller may touch.
 *
 * <p>Every test here exists because the reference implementation got that question wrong in a way that
 * produced no error — a scope bug does not throw, it just returns the wrong rows. The assertions are
 * therefore mostly about <b>which shape means "nothing"</b>, since the two dangerous readings ("empty means
 * everything", "an empty list is safe to skip") are each one line away from the correct one.
 *
 * <p>Flat rather than {@code @Nested}: the build's Surefire does not descend into nested classes under
 * JUnit Platform 6, so a nested suite reports success having run nothing at all.
 */
class OperatorScopeTest {

    private static final UUID OPERATOR_A = UUID.randomUUID();
    private static final UUID OPERATOR_B = UUID.randomUUID();

    // ───────────────────────────── the scope itself ─────────────────────────────

    @Test
    @DisplayName("a platform scope reaches every operator, including rows owned by none")
    void platform_reaches_everything() {
        OperatorScope scope = OperatorScope.allOperators();

        assertThat(scope.permits(OPERATOR_A)).isTrue();
        assertThat(scope.permits(OPERATOR_B)).isTrue();

        // A row belonging to no operator is reachable only by platform staff, so permits(null) answering
        // platform() is the rule and not an oversight. It is also what stops List.of(..).contains(null)
        // from throwing.
        assertThat(scope.permits(null)).isTrue();
    }

    @Test
    @DisplayName("an operator scope reaches its own operators and no others")
    void restricted_reaches_only_its_own() {
        OperatorScope scope = OperatorScope.restrictedTo(List.of(OPERATOR_A));

        assertThat(scope.permits(OPERATOR_A)).isTrue();
        assertThat(scope.permits(OPERATOR_B)).isFalse();
        assertThat(scope.permits(null)).isFalse();
    }

    @Test
    @DisplayName("many operators is a legal scope, not an error")
    void many_memberships_are_legal() {
        OperatorScope scope = OperatorScope.restrictedTo(List.of(OPERATOR_A, OPERATOR_B));

        // Five modules in the reference each refused a list of more than one, so a shared-services employee
        // covering two depots could not be represented at all.
        assertThat(scope.permits(OPERATOR_A)).isTrue();
        assertThat(scope.permits(OPERATOR_B)).isTrue();
    }

    @Test
    @DisplayName("the query filter is never empty, so the bind never fails")
    void filter_is_never_empty() {
        // JPA cannot bind an empty IN list. A platform scope has no operators to bind, so it yields a
        // sentinel instead — safe precisely because it matches nothing, and because the ':platform = true'
        // disjunct has already opened the query by the time it is evaluated.
        assertThat(OperatorScope.allOperators().filter()).hasSize(1);
        assertThat(OperatorScope.restrictedTo(List.of()).filter()).hasSize(1);

        assertThat(OperatorScope.restrictedTo(List.of(OPERATOR_A, OPERATOR_B)).filter())
                .containsExactly(OPERATOR_A, OPERATOR_B);
    }

    @Test
    @DisplayName("the sentinel matches no operator that could actually exist")
    void the_sentinel_matches_nothing() {
        // If the sentinel were a value a real row could carry, a platform scope would leak exactly the rows
        // carrying it. The nil uuid is not producible by Uuids.next().
        assertThat(OperatorScope.allOperators().filter()).containsExactly(new UUID(0L, 0L));
    }

    // ──────────────────── naming the operator a new row belongs to ────────────────────

    @Test
    @DisplayName("single-operator staff need not name one")
    void sole_operator_is_implied() {
        assertThat(OperatorScope.restrictedTo(List.of(OPERATOR_A)).requireTarget(null))
                .isEqualTo(OPERATOR_A);
    }

    @Test
    @DisplayName("platform staff must name one, and that is not backwards")
    void platform_must_choose() {
        // The most privileged caller is the one forced to supply the parameter. They reach every operator,
        // so the owner of a new row is genuinely undetermined — 400, not 403; nothing is being refused.
        assertThatThrownBy(() -> OperatorScope.allOperators().requireTarget(null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ScopeErrors.OPERATOR_REQUIRED);
    }

    @Test
    @DisplayName("multi-operator staff must choose between them")
    void ambiguous_scope_must_choose() {
        assertThatThrownBy(() -> OperatorScope.restrictedTo(List.of(OPERATOR_A, OPERATOR_B))
                .requireTarget(null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ScopeErrors.OPERATOR_REQUIRED);
    }

    @Test
    @DisplayName("naming an operator selects among those already reached — it never widens")
    void naming_confers_nothing() {
        OperatorScope scope = OperatorScope.restrictedTo(List.of(OPERATOR_A, OPERATOR_B));

        assertThat(scope.requireTarget(OPERATOR_B)).isEqualTo(OPERATOR_B);

        // THE WHOLE POINT of validating the ?operator= parameter here. It is a caller-supplied value, so it
        // is checked against the caller's own scope; otherwise it would be an authority field on a request,
        // which is the one thing authority never is.
        assertThatThrownBy(() -> OperatorScope.restrictedTo(List.of(OPERATOR_A)).requireTarget(OPERATOR_B))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ScopeErrors.NOT_AUTHORISED);
    }

    // ──────────────────── resolving a principal into a scope ────────────────────

    @Test
    @DisplayName("platform staff are scoped to every operator")
    void platform_staff_get_everything() {
        assertThat(resolve(staff(StaffTenancy.ROOT)).platform()).isTrue();
        assertThat(resolve(staff(StaffTenancy.ADMIN)).platform()).isTrue();
    }

    @Test
    @DisplayName("operator staff are scoped to exactly their memberships")
    void operator_staff_get_their_memberships() {
        OperatorScope scope = resolve(staff(StaffTenancy.OPERATOR, OPERATOR_A, OPERATOR_B));

        assertThat(scope.platform()).isFalse();
        assertThat(scope.operatorUids()).containsExactly(OPERATOR_A, OPERATOR_B);
    }

    @Test
    @DisplayName("operator staff serving nobody see nothing, not everything")
    void no_memberships_means_nothing() {
        // The opposite reading is one isEmpty() check away and is unrestricted access. Refusing here rather
        // than returning an empty scope means the mistake cannot be made downstream either.
        assertThatThrownBy(() -> resolve(staff(StaffTenancy.OPERATOR)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ScopeErrors.NOT_AUTHORISED);
    }

    @Test
    @DisplayName("a partner has no operator scope at all, in either direction")
    void partners_are_refused() {
        // Fail-closed because neither answer is safe: platform scope would hand one organisation every
        // operator's data, and operator scope is impossible since a partner holds no memberships.
        assertThatThrownBy(() -> resolve(staff(StaffTenancy.PARTNER)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ScopeErrors.NOT_AUTHORISED);
    }

    @Test
    @DisplayName("a principal with no tenancy at all is refused rather than defaulted")
    void missing_tenancy_is_refused() {
        // Reachable today only from a malformed token. It becomes the ordinary path once agents exist — an
        // agent's authority comes from selling grants, not a tenancy — so the branch is written and tested
        // now rather than discovered as a NullPointerException later. PrincipalType.AGENT does not exist
        // yet, so the non-STAFF branch itself gains its test in the agent-identity slice.
        Principal untenanted = new Principal(
                UUID.randomUUID(), PrincipalType.STAFF, null, List.of(), Set.of());

        assertThatThrownBy(() -> resolve(untenanted))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ScopeErrors.NOT_AUTHORISED);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private OperatorScope resolve(Principal principal) {
        return new OperatorScopeResolver(fixedContext(principal)).require();
    }

    private Principal staff(StaffTenancy tenancy, UUID... operatorUids) {
        return new Principal(UUID.randomUUID(), PrincipalType.STAFF, tenancy, List.of(operatorUids), Set.of());
    }

    /** A context that always returns the same actor — the resolver reads nothing else. */
    private PrincipalContext fixedContext(Principal principal) {
        return new PrincipalContext() {
            @Override
            public Principal require() {
                return principal;
            }

            @Override
            public Optional<Principal> current() {
                return Optional.of(principal);
            }
        };
    }
}
