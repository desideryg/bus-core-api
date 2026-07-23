package tz.co.otapp.buscore;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The cross-company guard, tested where it actually lives.
 *
 * <p>Nothing here goes through the application. The rule that an operator staff member may not hold
 * memberships across two companies is a <b>composite foreign key</b>, not a service check, so these tests
 * write SQL directly and assert that Postgres refuses it. Testing it through a service would prove only that
 * one caller checks — and the whole reason for putting it in the schema is the caller that forgets.
 *
 * <p>Why it matters: one credential reaching two unrelated businesses' data is a cross-tenant breach with no
 * exploit required, just a mis-click on an admin screen.
 */
@SpringBootTest(properties = "identity.bootstrap.root.password=bootstrap-secret-for-tests")
@Testcontainers
class StaffOperatorIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final UUID COMPANY_A = UUID.randomUUID();
    private static final UUID COMPANY_B = UUID.randomUUID();

    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM staff_operators");
        jdbc.update("DELETE FROM staff_identities WHERE tenancy <> 'ROOT'");
    }

    // ─────────────────────── who may have a company at all ───────────────────────

    @Test
    @DisplayName("operator staff must carry a company")
    void operator_staff_without_a_company_are_refused() {
        // Written as an equivalence rather than two checks, so neither half can be satisfied alone. Without
        // this, an operator staff member with a null company would be linkable to nobody — or, worse, the
        // composite key would silently stop constraining anything.
        assertThatThrownBy(() -> insertStaff("orphan", "OPERATOR", null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("platform staff must not carry one")
    void platform_staff_with_a_company_are_refused() {
        // ROOT and ADMIN belong to no tenancy, which is precisely why they may be scoped to all of it.
        // Giving one a company would make that claim false while changing nothing visible.
        assertThatThrownBy(() -> insertStaff("admin", "ADMIN", COMPANY_A))
                .isInstanceOf(DataAccessException.class);

        assertThatCode(() -> insertStaff("admin", "ADMIN", null)).doesNotThrowAnyException();
    }

    // ─────────────────────────── the guard itself ───────────────────────────

    @Test
    @DisplayName("a membership under the staff member's own company is accepted")
    void same_company_membership_is_accepted() {
        long staffId = insertStaff("jane", "OPERATOR", COMPANY_A);

        assertThatCode(() -> insertMembership(staffId, UUID.randomUUID(), COMPANY_A))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("many memberships under that company are accepted — one person, two depots")
    void many_memberships_under_one_company_are_accepted() {
        long staffId = insertStaff("jane", "OPERATOR", COMPANY_A);

        insertMembership(staffId, UUID.randomUUID(), COMPANY_A);
        insertMembership(staffId, UUID.randomUUID(), COMPANY_A);

        // The guard bounds the company, not the count. A shared-services employee covering two depots is one
        // account with two memberships, not two accounts with two passwords and two audit trails.
        assertThat(membershipsOf(staffId)).isEqualTo(2);
    }

    @Test
    @DisplayName("a membership under a different company is impossible")
    void cross_company_membership_is_refused() {
        long staffId = insertStaff("jane", "OPERATOR", COMPANY_A);

        // THE WHOLE POINT. Postgres enforces the pair (staff_identity_id, company_uid) against the staff
        // member's own row, so this row cannot be written at all — not by this application, not by a data
        // fix, not by a future admin surface that forgot to check.
        assertThatThrownBy(() -> insertMembership(staffId, UUID.randomUUID(), COMPANY_B))
                .isInstanceOf(DataAccessException.class);

        assertThat(membershipsOf(staffId)).isZero();
    }

    @Test
    @DisplayName("platform staff can hold no memberships")
    void platform_staff_cannot_be_linked() {
        long staffId = insertStaff("admin", "ADMIN", null);

        // Falls out of the same constraint rather than needing its own: their company_uid is null and the
        // membership's is NOT NULL, so no value would match. Correct — they reach every operator already,
        // and a membership would only narrow that or contradict it.
        assertThatThrownBy(() -> insertMembership(staffId, UUID.randomUUID(), null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertMembership(staffId, UUID.randomUUID(), COMPANY_A))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("linking the same operator twice is refused, so linking is idempotent and unlinking is total")
    void the_same_pair_cannot_be_linked_twice() {
        long staffId = insertStaff("jane", "OPERATOR", COMPANY_A);
        UUID operator = UUID.randomUUID();

        insertMembership(staffId, operator, COMPANY_A);

        // Without this, unlinking removes one of two rows and the caller keeps the access they were just
        // told they lost.
        assertThatThrownBy(() -> insertMembership(staffId, operator, COMPANY_A))
                .isInstanceOf(DataAccessException.class);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private long insertStaff(String username, String tenancy, UUID companyUid) {
        return jdbc.queryForObject("""
                INSERT INTO staff_identities (uid, username, email, display_name, tenancy, status,
                                              company_uid, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, now(), now())
                RETURNING id
                """, Long.class,
                UUID.randomUUID(), username, username + "@bus-core.local", username, tenancy, companyUid);
    }

    private void insertMembership(long staffId, UUID operatorUid, UUID companyUid) {
        jdbc.update("""
                INSERT INTO staff_operators (uid, staff_identity_id, operator_uid, company_uid,
                                             created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, UUID.randomUUID(), staffId, operatorUid, companyUid);
    }

    private int membershipsOf(long staffId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM staff_operators WHERE staff_identity_id = ?", Integer.class, staffId);
    }
}
