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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tz.co.otapp.buscore.identityaccess.internal.security.PinEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A session, and the three things a refresh token could not ship without: rotation, reuse detection, and
 * revocation.
 *
 * <p>Before this slice a sign-in returned only a short access token, and nothing about it could be ended
 * early — there was no server-side state to revoke. These tests are that state working: a session renews by
 * rotating a single-use token, a replayed token is treated as a theft and kills the session, and everything
 * that changes a credential or withdraws an account ends the sessions that must not outlive it.
 *
 * <p>The access token stays exactly what it was — stateless, trusted unread, short. The tests below check
 * the session it is paired with, not the token itself, which is why revocation here is proved by a refusal
 * to <em>refresh</em> rather than by an access token suddenly failing: an already-issued access token
 * outlives its session by at most its own TTL, and one test pins that trade so it is a decision and not a
 * surprise.
 */
@SpringBootTest(properties = "identity.bootstrap.root.password=bootstrap-secret-for-tests")
@AutoConfigureMockMvc
@Testcontainers
class SessionLifecycleIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String NEW_PASSWORD = "a-different-long-passphrase";
    private static final String PIN = "624913";
    private static final String AGENT_MSISDN = "+255712345678";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PinEncoder pinEncoder;

    @BeforeEach
    void reset() {
        // Refresh tokens reference sessions, so they go first. Sessions reference no identity — they hold a
        // bare principal uid — so the staff and agent rows can be cleared in any order after.
        jdbc.update("DELETE FROM auth_refresh_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM auth_audit_events");
        jdbc.update("DELETE FROM staff_password_resets");
        jdbc.update("DELETE FROM staff_roles WHERE staff_identity_id IN "
                + "(SELECT id FROM staff_identities WHERE tenancy <> 'ROOT')");
        jdbc.update("DELETE FROM staff_credentials WHERE staff_identity_id IN "
                + "(SELECT id FROM staff_identities WHERE tenancy <> 'ROOT')");
        jdbc.update("DELETE FROM staff_identities WHERE tenancy <> 'ROOT'");
        jdbc.update("DELETE FROM agent_credentials");
        jdbc.update("DELETE FROM agent_identities");
    }

    // ─────────────────────────── the happy path ───────────────────────────

    @Test
    @DisplayName("sign-in returns a refresh token, and it buys a fresh access token")
    void a_refresh_token_renews_a_session() throws Exception {
        givenStaff("jane");
        String refresh = refreshTokenOf(login("jane"));

        String renewed = mockMvc.perform(refresh(refresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.refreshToken").exists())
                .andReturn().getResponse().getContentAsString();

        // The renewed access token is a real one — it authenticates the endpoint that proves a token works.
        mockMvc.perform(get("/admin/v1/auth/me").header("Authorization", "Bearer " + field(renewed, "accessToken")))
                .andExpect(status().isOk());
    }

    // ─────────────────────── rotation and reuse detection ───────────────────────

    @Test
    @DisplayName("refreshing rotates the token: the one just spent no longer works")
    void the_spent_token_is_dead() throws Exception {
        givenStaff("jane");
        String first = refreshTokenOf(login("jane"));

        refreshTokenOf(mockMvc.perform(refresh(first)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        // The presented token was spent by the refresh above. Presenting it a second time is the replay that
        // reuse detection exists to catch.
        mockMvc.perform(refresh(first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.REFRESH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("replaying a spent token revokes the whole session, successor token included")
    void a_replay_kills_the_session() throws Exception {
        givenStaff("jane");
        String first = refreshTokenOf(login("jane"));
        String second = refreshTokenOf(mockMvc.perform(refresh(first)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        // Replay the spent token. Either the holder is retrying or someone else holds a copy — the two are
        // indistinguishable, so both are answered as the theft.
        mockMvc.perform(refresh(first)).andExpect(status().isUnauthorized());

        // The successor token, which WAS live, is now dead too: the session it belonged to was revoked, not
        // merely the replayed token refused. That is the difference between rotation and rotation with reuse
        // detection — a stolen token cannot outlive the moment the theft is noticed.
        mockMvc.perform(refresh(second))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.REFRESH_TOKEN_INVALID"));

        assertThat(revokedReasonFor("jane")).isEqualTo("REFRESH_TOKEN_REUSED");
        assertThat(eventTypes()).contains("REFRESH_TOKEN_REUSED");
    }

    // ─────────────────────────────── logout ───────────────────────────────

    @Test
    @DisplayName("logout ends the session it names")
    void logout_ends_a_session() throws Exception {
        givenStaff("jane");
        String refresh = refreshTokenOf(login("jane"));

        mockMvc.perform(logout(refresh)).andExpect(status().isOk());

        mockMvc.perform(refresh(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.REFRESH_TOKEN_INVALID"));
        assertThat(eventTypes()).contains("LOGGED_OUT");
    }

    @Test
    @DisplayName("logout is idempotent and says nothing about which tokens are real")
    void logout_is_idempotent() throws Exception {
        givenStaff("jane");
        String refresh = refreshTokenOf(login("jane"));

        // A token that names no live session is the state logout wants, so it is success, not a refusal —
        // and a public endpoint that answered otherwise would confirm which tokens exist.
        mockMvc.perform(logout("a-token-that-never-existed")).andExpect(status().isOk());
        mockMvc.perform(logout(refresh)).andExpect(status().isOk());
        mockMvc.perform(logout(refresh)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("logout ends only the session it names, not the holder's others")
    void logout_is_per_session() throws Exception {
        givenStaff("jane");
        // Two sign-ins: two sessions, as if from two devices.
        String phone = refreshTokenOf(login("jane"));
        String laptop = refreshTokenOf(login("jane"));

        mockMvc.perform(logout(phone)).andExpect(status().isOk());

        // Signing out the phone must not sign out the laptop — that is logout-everywhere, a different act.
        mockMvc.perform(refresh(phone)).andExpect(status().isUnauthorized());
        mockMvc.perform(refresh(laptop)).andExpect(status().isOk());
    }

    // ─────────────────────── revocation when things change ───────────────────────

    @Test
    @DisplayName("suspending an account ends its sessions — and its access token outlives them by its TTL")
    void suspension_revokes_sessions() throws Exception {
        String adminToken = platformAdmin("admin");
        givenStaff("jane");
        String body = login("jane");
        String access = field(body, "accessToken");
        String refresh = refreshTokenOf(body);

        mockMvc.perform(suspend(staffUid("jane"), adminToken)).andExpect(status().isOk());

        // The session is gone: it cannot be refreshed.
        mockMvc.perform(refresh(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.REFRESH_TOKEN_INVALID"));

        // But the access token already in the holder's hand still works until it expires — it is stateless
        // and read against no table. THIS IS THE DELIBERATE TRADE of a stateless access token: revocation
        // acts on the session, and the access token outlives it by at most its short TTL. Pinned here so the
        // trade is a decision rather than a latent surprise.
        mockMvc.perform(get("/admin/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("changing a password ends sessions opened under the old one")
    void a_password_change_revokes_sessions() throws Exception {
        givenStaff("jane");
        String refresh = refreshTokenOf(login("jane"));

        mockMvc.perform(changePassword("jane", PASSWORD, NEW_PASSWORD)).andExpect(status().isOk());

        mockMvc.perform(refresh(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.REFRESH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("recovering an account by reset ends every session it had")
    void a_reset_revokes_sessions() throws Exception {
        String adminToken = platformAdmin("admin");
        givenStaff("jane");
        String refresh = refreshTokenOf(login("jane"));

        // A forgotten-password recovery is exactly the moment an account may have been taken over. A session
        // left renewing after it would be the attacker's, not the holder's.
        String token = issueResetFor("jane", adminToken);
        mockMvc.perform(redeem(token, NEW_PASSWORD)).andExpect(status().isOk());

        mockMvc.perform(refresh(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.REFRESH_TOKEN_INVALID"));
    }

    // ─────────────────────── expiry, storage, and the gate ───────────────────────

    @Test
    @DisplayName("a session past its absolute expiry cannot be refreshed")
    void a_lapsed_session_is_refused() throws Exception {
        givenStaff("jane");
        String refresh = refreshTokenOf(login("jane"));

        // 'AT TIME ZONE UTC' is load-bearing, exactly as for reset-token expiry: the driver sets the session
        // timezone from the JVM's, so a bare now() is local wall-clock while the application writes these
        // columns as UTC. On a machine east of Greenwich a bare now() would put the expiry in the future.
        jdbc.update("UPDATE auth_sessions SET expires_at = (now() AT TIME ZONE 'UTC') - interval '1 minute'");

        mockMvc.perform(refresh(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.REFRESH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("the refresh token is stored only as a fingerprint")
    void only_a_fingerprint_is_stored() throws Exception {
        givenStaff("jane");
        String refresh = refreshTokenOf(login("jane"));

        String stored = jdbc.queryForObject("SELECT token_hash FROM auth_refresh_tokens", String.class);

        // A refresh token is a bearer credential: read access to this table must not be equivalent to a live
        // session on every account that holds one.
        assertThat(stored).isNotEqualTo(refresh).hasSize(64);
    }

    @Test
    @DisplayName("a staff refresh token is refused at the agent door")
    void the_surface_is_enforced_on_refresh() throws Exception {
        givenStaff("jane");
        String staffRefresh = refreshTokenOf(login("jane"));

        // The audience gate keeps a staff access token off agent routes on every ordinary call; the same
        // separation must hold at the one endpoint that mints new access tokens, or it would be a hole in
        // the gate. A staff refresh token on the agent door is refused as an unknown one.
        mockMvc.perform(post("/agent/v1/auth/refresh")
                .contentType("application/json")
                .content("{\"refreshToken\":\"" + staffRefresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.REFRESH_TOKEN_INVALID"));
    }

    // ─────────────────────────────── the agent surface ───────────────────────────────

    @Test
    @DisplayName("an agent session refreshes and logs out just as a staff one does")
    void an_agent_session_has_the_same_lifecycle() throws Exception {
        givenAgent(AGENT_MSISDN);
        String first = refreshTokenOf(agentLogin());

        String second = refreshTokenOf(mockMvc.perform(agentRefresh(first)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(agentLogout(second)).andExpect(status().isOk());
        mockMvc.perform(agentRefresh(second))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.REFRESH_TOKEN_INVALID"));
    }

    // ───────────────────────────── request builders ─────────────────────────────

    private MockHttpServletRequestBuilder refresh(String token) {
        return post("/admin/v1/auth/refresh")
                .contentType("application/json")
                .content("{\"refreshToken\":\"" + token + "\"}");
    }

    private MockHttpServletRequestBuilder logout(String token) {
        return post("/admin/v1/auth/logout")
                .contentType("application/json")
                .content("{\"refreshToken\":\"" + token + "\"}");
    }

    private MockHttpServletRequestBuilder agentRefresh(String token) {
        return post("/agent/v1/auth/refresh")
                .contentType("application/json")
                .content("{\"refreshToken\":\"" + token + "\"}");
    }

    private MockHttpServletRequestBuilder agentLogout(String token) {
        return post("/agent/v1/auth/logout")
                .contentType("application/json")
                .content("{\"refreshToken\":\"" + token + "\"}");
    }

    private MockHttpServletRequestBuilder changePassword(String identifier, String current, String replacement) {
        return post("/admin/v1/auth/password")
                .contentType("application/json")
                .content("{\"identifier\":\"" + identifier + "\",\"currentPassword\":\"" + current
                        + "\",\"newPassword\":\"" + replacement + "\"}");
    }

    private MockHttpServletRequestBuilder redeem(String token, String newPassword) {
        return post("/admin/v1/auth/password/redeem")
                .contentType("application/json")
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}");
    }

    private MockHttpServletRequestBuilder suspend(UUID staffUid, String adminToken) {
        return post("/admin/v1/staff/uid/" + staffUid + "/suspension")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"status\":\"SUSPENDED\",\"reason\":\"session lifecycle test\"}");
    }

    // ───────────────────────────── sign-in helpers ─────────────────────────────

    private String login(String username) throws Exception {
        return mockMvc.perform(post("/admin/v1/auth/login")
                .contentType("application/json")
                .content("{\"identifier\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String agentLogin() throws Exception {
        return mockMvc.perform(post("/agent/v1/auth/login")
                .contentType("application/json")
                .content("{\"msisdn\":\"" + AGENT_MSISDN + "\",\"pin\":\"" + PIN + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String platformAdmin(String username) throws Exception {
        givenStaff(username);
        givenRole(username, "PLATFORM_ADMIN");
        return field(login(username), "accessToken");
    }

    private String issueResetFor(String username, String adminToken) throws Exception {
        String body = mockMvc.perform(post("/admin/v1/staff/uid/" + staffUid(username) + "/password-reset")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return field(body, "token");
    }

    private static String refreshTokenOf(String responseBody) {
        return field(responseBody, "refreshToken");
    }

    private static String field(String responseBody, String name) {
        return responseBody.replaceAll(".*\"" + name + "\":\"([^\"]+)\".*", "$1");
    }

    // ───────────────────────────── fixtures and reads ─────────────────────────────

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

    private void givenAgent(String canonicalMsisdn) {
        jdbc.update("""
                INSERT INTO agent_identities (uid, msisdn, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', now(), now())
                """, UUID.randomUUID(), canonicalMsisdn, "Agent " + canonicalMsisdn);
        jdbc.update("""
                INSERT INTO agent_credentials (uid, agent_identity_id, pin_hash, pin_updated_at,
                                               failed_attempts, must_change_pin, created_at, updated_at)
                VALUES (?, (SELECT id FROM agent_identities WHERE msisdn = ?), ?, now(), 0, false, now(), now())
                """, UUID.randomUUID(), canonicalMsisdn, pinEncoder.encode(PIN));
    }

    private UUID staffUid(String username) {
        return jdbc.queryForObject("SELECT uid FROM staff_identities WHERE username = ?", UUID.class, username);
    }

    private String revokedReasonFor(String username) {
        return jdbc.queryForObject(
                "SELECT revoked_reason FROM auth_sessions WHERE principal_uid = ?", String.class,
                staffUid(username));
    }

    private java.util.List<String> eventTypes() {
        return jdbc.queryForList("SELECT event_type FROM auth_audit_events", String.class);
    }
}
