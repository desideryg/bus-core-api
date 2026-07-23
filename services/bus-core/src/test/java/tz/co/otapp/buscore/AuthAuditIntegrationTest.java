package tz.co.otapp.buscore;

import java.util.List;
import java.util.Map;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authentication trail.
 *
 * <p>Everything here is about one property: <b>the rows that matter most are written on paths that then
 * reject the request.</b> A trail that is emptiest exactly when something is going wrong is worse than no
 * trail, because it will be believed.
 */
@SpringBootTest(properties = "identity.bootstrap.root.password=bootstrap-secret-for-tests")
@AutoConfigureMockMvc
@Testcontainers
class AuthAuditIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM auth_audit_events");
        jdbc.update("DELETE FROM staff_roles WHERE staff_identity_id IN "
                + "(SELECT id FROM staff_identities WHERE tenancy <> 'ROOT')");
        jdbc.update("DELETE FROM staff_credentials WHERE staff_identity_id IN "
                + "(SELECT id FROM staff_identities WHERE tenancy <> 'ROOT')");
        jdbc.update("DELETE FROM staff_identities WHERE tenancy <> 'ROOT'");
    }

    @Test
    @DisplayName("a failed sign-in is recorded even though the request is rejected")
    void failure_survives_the_rejection() throws Exception {
        givenStaff("jane");

        mockMvc.perform(login("jane", "wrong")).andExpect(status().isUnauthorized());

        // THE WHOLE POINT. The recorder runs in its own transaction, so the record of the failure does not
        // roll back with the failure. Without REQUIRES_NEW this reads zero, and the trail is emptiest
        // exactly when it is most wanted.
        assertThat(eventsOfType("LOGIN_FAILURE")).hasSize(1);
    }

    @Test
    @DisplayName("an attempt against an account that does not exist is recorded, with no principal")
    void unknown_account_attempts_are_kept() throws Exception {
        mockMvc.perform(login("administrator", "guess")).andExpect(status().isUnauthorized());
        mockMvc.perform(login("root2", "guess")).andExpect(status().isUnauthorized());
        mockMvc.perform(login("admin", "guess")).andExpect(status().isUnauthorized());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT identifier_used, principal_uid FROM auth_audit_events WHERE event_type = 'LOGIN_FAILURE'");

        // These rows have no principal, which is why the column is nullable — and a run of them against
        // 'administrator', 'root2', 'admin' is what a spray looks like. A NOT NULL constraint would have
        // forced the code to skip them, and the pattern would be invisible.
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("principal_uid")).isNull());
        assertThat(rows).extracting(row -> row.get("identifier_used"))
                .containsExactlyInAnyOrder("administrator", "root2", "admin");
    }

    @Test
    @DisplayName("the moment an account locks is its own event")
    void locking_is_recorded_separately_from_the_failure()throws Exception {
        givenStaff("target");

        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(login("target", "wrong-" + attempt));
        }

        // "The fifth failure" and "the moment it locked" are different questions, and an investigation
        // asks the second one. Five failures, one lock.
        assertThat(eventsOfType("LOGIN_FAILURE")).hasSize(5);
        assertThat(eventsOfType("ACCOUNT_LOCKED")).hasSize(1);
    }

    @Test
    @DisplayName("an attempt against an already-locked account is distinguishable from a wrong password")
    void blocked_by_lockout_is_its_own_event() throws Exception {
        givenStaff("locked");
        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(login("locked", "wrong-" + attempt));
        }

        mockMvc.perform(login("locked", PASSWORD)).andExpect(status().isLocked());

        // Someone still trying after the lock is a different signal from someone mistyping. Folding both
        // into LOGIN_FAILURE would hide it.
        assertThat(eventsOfType("LOGIN_BLOCKED_BY_LOCKOUT")).hasSize(1);
    }

    @Test
    @DisplayName("a successful sign-in is recorded against the account")
    void success_names_the_principal() throws Exception {
        givenStaff("jane");

        mockMvc.perform(login("jane", PASSWORD)).andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT principal_uid, principal_type, identifier_used FROM auth_audit_events "
                        + "WHERE event_type = 'LOGIN_SUCCESS'");
        assertThat(row.get("principal_uid")).isNotNull();
        assertThat(row.get("principal_type")).isEqualTo("STAFF");
        assertThat(row.get("identifier_used")).isEqualTo("jane");
    }

    @Test
    @DisplayName("the caller's address is recorded, not left null")
    void source_address_is_captured() throws Exception {
        givenStaff("jane");

        mockMvc.perform(login("jane", PASSWORD).header("X-Forwarded-For", "203.0.113.7, 10.0.0.1"));

        // The reference implementation passed null for the address on one of its two sign-in paths, which
        // left an entire population of rows unattributable — on exactly the population the trail called its
        // primary detection surface. Reading it from the request rather than threading it through service
        // signatures is what stops that being possible.
        String ip = jdbc.queryForObject(
                "SELECT source_ip FROM auth_audit_events WHERE event_type = 'LOGIN_SUCCESS'", String.class);
        assertThat(ip).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("a forged newline in the identifier cannot forge a trail entry")
    void identifier_is_sanitised() throws Exception {
        mockMvc.perform(login("bob\\n2026-01-01 INFO granted ROOT", "guess"));

        String stored = jdbc.queryForObject(
                "SELECT identifier_used FROM auth_audit_events LIMIT 1", String.class);

        // The trail is read by people during an incident, and rendered into logs and screens. A value the
        // caller controls must not be able to write what looks like a second entry.
        assertThat(stored).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    @DisplayName("granting and revoking a role are recorded")
    void administration_is_recorded() throws Exception {
        givenStaff("admin");
        givenRole("admin", "PLATFORM_ADMIN");
        givenStaff("recipient");
        String token = tokenFor("admin");
        UUID target = staffUid("recipient");

        mockMvc.perform(post("/admin/v1/staff/uid/" + target + "/roles")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"roleCode\":\"SUPPORT\"}"))
                .andExpect(status().isOk());

        // Who can do what is the thing an audit is ultimately about; a change to it that leaves no trace
        // is the one change nobody can reconstruct afterwards.
        assertThat(eventsOfType("ROLE_GRANTED")).hasSize(1);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private List<Map<String, Object>> eventsOfType(String type) {
        return jdbc.queryForList("SELECT * FROM auth_audit_events WHERE event_type = ?", type);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
            String identifier, String password) {
        return post("/admin/v1/auth/login")
                .contentType("application/json")
                .content("{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}");
    }

    private String tokenFor(String username) throws Exception {
        return mockMvc.perform(login(username, PASSWORD))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private UUID staffUid(String username) {
        return jdbc.queryForObject("SELECT uid FROM staff_identities WHERE username = ?", UUID.class, username);
    }

    private void givenStaff(String username) {
        jdbc.update("""
                INSERT INTO staff_identities (uid, username, email, display_name, tenancy, status,
                                              company_uid, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ADMIN', 'ACTIVE', NULL, now(), now())
                """, UUID.randomUUID(), username, username + "@bus-core.local", username);
        jdbc.update("""
                INSERT INTO staff_credentials (uid, staff_identity_id, password_hash, password_updated_at,
                                               failed_attempts, must_change_password, created_at, updated_at)
                VALUES (?, (SELECT id FROM staff_identities WHERE username = ?), ?, now(), 0, false, now(), now())
                """, UUID.randomUUID(), username, passwordEncoder.encode(PASSWORD));
    }

    private void givenRole(String username, String roleCode) {
        jdbc.update("""
                INSERT INTO staff_roles (uid, staff_identity_id, role_id, created_at, updated_at)
                VALUES (?, (SELECT id FROM staff_identities WHERE username = ?),
                           (SELECT id FROM roles WHERE code = ?), now(), now())
                """, UUID.randomUUID(), username, roleCode);
    }
}
