package tz.co.otapp.buscore;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tz.co.otapp.buscore.shared.time.Times;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff sign-in, end to end, against a real PostgreSQL that this test starts and throws away.
 *
 * <h2>Why a container and not the developer's database</h2>
 *
 * <p>A test that writes to a shared instance leaves rows behind, fails differently depending on what ran
 * before it, and cannot run on CI where that instance does not exist.
 *
 * <p>More importantly, an empty container is the only thing that proves the migration. Run against an
 * already-migrated database and the migration is never exercised — a broken one, or an entity that
 * disagrees with the DDL, would pass here and fail on the next fresh deployment. <b>If this test passes,
 * the schema and the entities agree</b>, because {@code ddl-auto=validate} checked them against tables
 * Flyway had just created.
 *
 * <h2>Setup goes in through JDBC, not through the API</h2>
 *
 * <p>There is no endpoint for creating a staff account yet — that is a later slice. Rather than invent one
 * for the test's convenience, rows are inserted directly. The password hash still comes from the real
 * {@link PasswordEncoder} bean, so the verification path being tested is the production one.
 */
@SpringBootTest(properties = {
        // A ROOT is created only when a password is configured, so the test configures one and then
        // asserts what that account can and cannot do.
        "identity.bootstrap.root.password=bootstrap-secret-for-tests"
})
@AutoConfigureMockMvc
@Testcontainers
class StaffLoginIntegrationTest {

    /**
     * PostgreSQL 18, matching production. Not an embedded database: the schema uses a partial unique index
     * and functional indexes on {@code lower(...)}, and an engine that silently accepted or ignored either
     * would let this test pass while the real migration failed.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetClockAndData() {
        Times.reset();
        // Each test starts from a known state. The ROOT account created by the bootstrap is left alone —
        // one of the tests below is about it.
        jdbc.update("DELETE FROM staff_credentials WHERE staff_identity_id IN "
                + "(SELECT id FROM staff_identities WHERE tenancy <> 'ROOT')");
        jdbc.update("DELETE FROM staff_identities WHERE tenancy <> 'ROOT'");
    }

    // ─────────────────────────────── the migration itself ───────────────────────────────

    @Test
    @DisplayName("the migration ran, in this module's own history table")
    void migration_applied() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history_identity_access WHERE success", Integer.class);

        // Names the per-module history table explicitly: if the wiring ever falls back to Flyway's single
        // default instance, this query fails because that table does not exist.
        assertThat(applied).isPositive();
    }

    @Test
    @DisplayName("at most one ROOT can ever exist")
    void single_root_is_enforced_by_the_database() {
        // The service-layer check cannot guarantee this — two concurrent bootstraps both see none. The
        // partial unique index is the real guard, so it is the real guard that gets tested.
        assertThat(insertFails("second-root", "ROOT", "ACTIVE"))
                .as("a second ROOT must be refused by the index, not by application code")
                .isTrue();
    }

    @Test
    @DisplayName("usernames are unique regardless of case")
    void case_insensitive_uniqueness_is_enforced() {
        givenStaff("Alice", "ACTIVE", false);

        assertThat(insertFails("alice", "ADMIN", "ACTIVE"))
                .as("Alice and alice must not both exist — the functional index is what prevents it")
                .isTrue();
    }

    // ─────────────────────────────────── signing in ───────────────────────────────────

    @Test
    @DisplayName("correct credentials return a token, and the token identifies the caller")
    void login_then_use_the_token() throws Exception {
        givenStaff("jane.doe", "ACTIVE", false);

        String token = mockMvc.perform(loginAs("jane.doe", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/admin/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("jane.doe"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                // The numeric primary key must never appear on the wire.
                .andExpect(jsonPath("$.data.id").doesNotExist());
    }

    @Test
    @DisplayName("an email address works as the identifier too")
    void login_by_email() throws Exception {
        givenStaff("jane.doe", "ACTIVE", false);

        mockMvc.perform(loginAs("jane.doe@bus-core.local", PASSWORD))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a protected route without a token is refused in the standard envelope")
    void security_refusals_use_the_same_envelope() throws Exception {
        // Spring Security's default refusal is an empty body with a WWW-Authenticate header — which would
        // be the one response shape in the whole application that is not the envelope, in precisely the
        // case where a client is already having a bad time. This lives here rather than in the smoke test
        // because the security chain belongs to identity-access, which needs a database to start.
        mockMvc.perform(get("/admin/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AUTH.NOT_AUTHENTICATED"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    @DisplayName("past the chain, an unknown path is a 404 in the envelope, not a 500")
    void unknown_path_is_not_an_internal_error() throws Exception {
        givenStaff("explorer", "ACTIVE", false);
        String token = tokenFor("explorer");

        // REGRESSION. The blanket Exception handler once swallowed Spring's own status-carrying
        // exceptions, so this answered 500 and logged a stack trace at ERROR — every crawler and typo'd
        // URL looking like an outage, burying the failures that were one. It lives here rather than in the
        // smoke test because a request must get PAST the security chain to reach the dispatcher at all.
        mockMvc.perform(get("/definitely-not-a-route").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON.NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("a forged or expired token is refused exactly like no token at all")
    void a_broken_token_is_indistinguishable_from_none() throws Exception {
        // The filter never reports why a token was unusable. Distinguishing "expired" from "bad signature"
        // would tell an attacker whether a forgery was close.
        mockMvc.perform(get("/admin/v1/auth/me").header("Authorization", "Bearer not.a.real.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.NOT_AUTHENTICATED"));
    }

    // ──────────────────── the refusals, which must be indistinguishable ────────────────────

    @Test
    @DisplayName("unknown user, wrong password and a suspended account answer identically")
    void refusals_are_indistinguishable() throws Exception {
        givenStaff("wrong.password", "ACTIVE", false);
        givenStaff("suspended.user", "SUSPENDED", false);

        // THE POINT OF THIS TEST. Three quite different internal situations, one answer — otherwise the
        // sign-in endpoint becomes a free tool for discovering which accounts exist.
        expectSameRefusal(loginAs("no.such.person", PASSWORD));
        expectSameRefusal(loginAs("wrong.password", "not-the-password"));
        expectSameRefusal(loginAs("suspended.user", PASSWORD));
    }

    @Test
    @DisplayName("five consecutive failures lock the account")
    void lockout_after_repeated_failures() throws Exception {
        givenStaff("target", "ACTIVE", false);

        for (int attempt = 1; attempt <= 5; attempt++) {
            expectSameRefusal(loginAs("target", "wrong-" + attempt));
        }

        // Distinguishable, deliberately: an employee told only "invalid credentials" retries and deepens
        // the lock. The enumeration this permits is the accepted cost, documented on the error code.
        mockMvc.perform(loginAs("target", "wrong-again"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("AUTH.ACCOUNT_LOCKED"));
    }

    @Test
    @DisplayName("a locked account is refused even with the correct password")
    void lockout_is_checked_before_the_password() throws Exception {
        givenStaff("locked.out", "ACTIVE", false);
        for (int attempt = 1; attempt <= 5; attempt++) {
            expectSameRefusal(loginAs("locked.out", "wrong-" + attempt));
        }

        // The ordering rule made observable: a lock that let the right password through would stop nothing,
        // because the attacker's last guess is the one that matters.
        mockMvc.perform(loginAs("locked.out", PASSWORD))
                .andExpect(status().isLocked());
    }

    @Test
    @DisplayName("a successful sign-in clears the failure counter")
    void failures_must_be_consecutive() throws Exception {
        givenStaff("resilient", "ACTIVE", false);

        expectSameRefusal(loginAs("resilient", "wrong"));
        expectSameRefusal(loginAs("resilient", "wrong"));
        mockMvc.perform(loginAs("resilient", PASSWORD)).andExpect(status().isOk());

        // Were the counter not cleared, four more failures would lock an account that has just proved
        // itself — locking out real people is the cost of getting this wrong.
        for (int attempt = 1; attempt <= 4; attempt++) {
            expectSameRefusal(loginAs("resilient", "wrong-" + attempt));
        }
        mockMvc.perform(loginAs("resilient", PASSWORD)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the failure counter survives the rejection it accompanies")
    void failure_count_is_committed_despite_the_thrown_refusal() throws Exception {
        givenStaff("counted", "ACTIVE", false);

        expectSameRefusal(loginAs("counted", "wrong"));

        Integer failures = jdbc.queryForObject(
                "SELECT c.failed_attempts FROM staff_credentials c "
                        + "JOIN staff_identities i ON i.id = c.staff_identity_id WHERE i.username = 'counted'",
                Integer.class);

        // Without noRollbackFor on the service, this reads 0: the increment is discarded along with the
        // exception, the lockout never triggers, and NOTHING anywhere fails. That silence is why it is
        // asserted directly rather than inferred from the lockout test.
        assertThat(failures).isEqualTo(1);
    }

    // ───────────────────────────────── the bootstrap ─────────────────────────────────

    @Test
    @DisplayName("the bootstrap ROOT exists but must change its password before it can be used")
    void root_is_created_and_must_rotate() throws Exception {
        Integer roots = jdbc.queryForObject(
                "SELECT count(*) FROM staff_identities WHERE tenancy = 'ROOT'", Integer.class);
        assertThat(roots).isEqualTo(1);

        // The correct password, and still no token: the configured value has necessarily been seen by
        // whoever deployed it, so the account cannot be usable until it is rotated.
        mockMvc.perform(loginAs("root", "bootstrap-secret-for-tests"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH.PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ─────────────────────────────────── helpers ───────────────────────────────────

    private org.springframework.test.web.servlet.RequestBuilder loginAs(String identifier, String password) {
        return post("/admin/v1/auth/login")
                .contentType("application/json")
                .content("{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}");
    }

    /** Every refusal that must look the same, asserted in one place so they cannot drift apart. */
    private void expectSameRefusal(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /** Sign in and return the bearer token, for tests that need to get past the chain. */
    private String tokenFor(String username) throws Exception {
        return mockMvc.perform(loginAs(username, PASSWORD))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private void givenStaff(String username, String status, boolean mustChangePassword) {
        jdbc.update("""
                INSERT INTO staff_identities (uid, username, email, display_name, tenancy, status,
                                              created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ADMIN', ?, now(), now())
                """, UUID.randomUUID(), username, username + "@bus-core.local", username, status);

        jdbc.update("""
                INSERT INTO staff_credentials (uid, staff_identity_id, password_hash, password_updated_at,
                                               failed_attempts, must_change_password, created_at, updated_at)
                VALUES (?, (SELECT id FROM staff_identities WHERE username = ?), ?, now(), 0, ?, now(), now())
                """, UUID.randomUUID(), username, passwordEncoder.encode(PASSWORD), mustChangePassword);
    }

    /** Whether the database refuses an insert — used to assert a constraint rather than assume it. */
    private boolean insertFails(String username, String tenancy, String status) {
        try {
            jdbc.update("""
                    INSERT INTO staff_identities (uid, username, email, display_name, tenancy, status,
                                                  created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, now(), now())
                    """, UUID.randomUUID(), username, username + "@bus-core.local", username, tenancy, status);
            return false;
        } catch (org.springframework.dao.DataAccessException refusedByConstraint) {
            return true;
        }
    }
}
