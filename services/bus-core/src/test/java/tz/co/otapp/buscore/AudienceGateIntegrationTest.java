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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audience gate: the right kind of caller on the right surface.
 *
 * <h2>Half of this is testable today, and half is not</h2>
 *
 * <p>Only staff principals exist, so "an agent is kept off the staff surface" cannot be exercised until
 * agents arrive. What <b>can</b> be exercised is the symmetric guarantee — <b>staff are kept off the agent
 * surface</b> — and it is the more useful half to have working first, because the agent surface is
 * reserved before anything serves it.
 *
 * <p>That reservation is the point of landing this now. When agent routes appear they appear already
 * closed to staff, rather than being retrofitted across a surface that has grown in the meantime.
 */
@SpringBootTest(properties = "identity.bootstrap.root.password=bootstrap-secret-for-tests")
@AutoConfigureMockMvc
@Testcontainers
class AudienceGateIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearStaff() {
        jdbc.update("DELETE FROM staff_roles WHERE staff_identity_id IN "
                + "(SELECT id FROM staff_identities WHERE tenancy <> 'ROOT')");
        jdbc.update("DELETE FROM staff_credentials WHERE staff_identity_id IN "
                + "(SELECT id FROM staff_identities WHERE tenancy <> 'ROOT')");
        jdbc.update("DELETE FROM staff_identities WHERE tenancy <> 'ROOT'");
    }

    @Test
    @DisplayName("staff are refused on the agent surface, with a code of its own")
    void staff_cannot_reach_the_agent_surface() throws Exception {
        givenStaff("jane");
        String token = tokenFor("jane");

        // Nothing serves this path — but the PREFIX is reserved, and the gate fires before the dispatcher
        // would 404. A caller learns they are on the wrong surface rather than that the route is missing.
        mockMvc.perform(get("/agent/v1/anything").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AUTH.AUDIENCE_MISMATCH"));
    }

    @Test
    @DisplayName("the refusal is distinct from lacking a permission")
    void audience_and_permission_are_different_answers() throws Exception {
        givenStaff("jane");
        String token = tokenFor("jane");

        // Same status, deliberately different codes. One is fixed by a grant; the other cannot be fixed at
        // all. A support desk that cannot tell them apart cannot answer either.
        mockMvc.perform(get("/admin/v1/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON.FORBIDDEN"));

        mockMvc.perform(get("/agent/v1/anything").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH.AUDIENCE_MISMATCH"));
    }

    @Test
    @DisplayName("staff reach their own surface unimpeded")
    void staff_reach_the_staff_surface() throws Exception {
        givenStaff("jane");
        givenRole("jane", "SUPPORT");

        mockMvc.perform(get("/admin/v1/roles").header("Authorization", "Bearer " + tokenFor("jane")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unauthenticated caller is told they are unauthenticated, not that they are the wrong kind")
    void missing_credential_is_reported_as_such() throws Exception {
        // An absent principal is not an audience mismatch. Refusing here first would report the wrong
        // problem — and would tell an unauthenticated caller which prefixes exist.
        mockMvc.perform(get("/agent/v1/anything"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.NOT_AUTHENTICATED"));
    }

    @Test
    @DisplayName("paths outside any audience prefix are untouched by the gate")
    void unscoped_paths_are_not_the_gate_s_business() throws Exception {
        // The walking skeleton and actuator belong to no audience. If the gate had an opinion about every
        // path, adding one would mean revisiting it.
        mockMvc.perform(get("/ping")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private String tokenFor(String username) throws Exception {
        return mockMvc.perform(post("/admin/v1/auth/login")
                .contentType("application/json")
                .content("{\"identifier\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
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
