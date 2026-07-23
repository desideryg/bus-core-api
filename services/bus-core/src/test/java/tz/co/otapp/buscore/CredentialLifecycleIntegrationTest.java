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
import org.springframework.test.web.servlet.RequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * How an account gets a password, and how it gets a different one.
 *
 * <p>Before this slice both ends were dead: an account provisioned {@code PENDING} had no credential and
 * nobody could ever sign in to it, and an account flagged to rotate its password was refused a token with
 * no route that could complete the rotation. The first two tests here are those two dead ends.
 */
@SpringBootTest(properties = "identity.bootstrap.root.password=bootstrap-secret-for-tests")
@AutoConfigureMockMvc
@Testcontainers
class CredentialLifecycleIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String NEW_PASSWORD = "a-different-long-passphrase";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM auth_audit_events");
        jdbc.update("DELETE FROM staff_password_resets");
        jdbc.update("DELETE FROM staff_roles WHERE staff_identity_id IN "
                + "(SELECT id FROM staff_identities WHERE tenancy <> 'ROOT')");
        jdbc.update("DELETE FROM staff_credentials WHERE staff_identity_id IN "
                + "(SELECT id FROM staff_identities WHERE tenancy <> 'ROOT')");
        jdbc.update("DELETE FROM staff_identities WHERE tenancy <> 'ROOT'");
    }

    // ─────────────────────── the two dead ends this slice closes ───────────────────────

    @Test
    @DisplayName("a provisioned account gets its first password and becomes usable")
    void provisioning_completes_end_to_end() throws Exception {
        String adminToken = platformAdmin("admin");

        // Provisioned PENDING, with no credential — unusable by anybody, including its creator.
        mockMvc.perform(post("/admin/v1/staff")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("""
                        {"username":"nova","email":"nova@bus-core.local","displayName":"Nova","tenancy":"ADMIN"}
                        """))
                .andExpect(status().isCreated());

        String token = issueResetFor("nova", adminToken);
        mockMvc.perform(redeem(token, NEW_PASSWORD)).andExpect(status().isOk());

        // PENDING exists precisely to mean "provisioned, no password yet", so setting one ends it.
        assertThat(statusOf("nova")).isEqualTo("ACTIVE");
        mockMvc.perform(loginAs("nova", NEW_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a forced rotation can actually be completed")
    void forced_rotation_completes() throws Exception {
        givenStaff("jane");
        jdbc.update("UPDATE staff_credentials SET must_change_password = true");

        // No token is issued — deliberately, so a rotation-pending account is not quietly fully usable.
        // Which is exactly why the change route cannot require one.
        mockMvc.perform(loginAs("jane", PASSWORD)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH.PASSWORD_CHANGE_REQUIRED"));

        mockMvc.perform(changePassword("jane", PASSWORD, NEW_PASSWORD)).andExpect(status().isOk());

        // The flag is cleared by the change itself rather than by the caller remembering to clear it.
        // Left set, the holder would be refused a token forever with the password they were told to set.
        mockMvc.perform(loginAs("jane", NEW_PASSWORD)).andExpect(status().isOk());
    }

    // ─────────────────────────── changing a password ───────────────────────────

    @Test
    @DisplayName("the old password stops working the moment the new one starts")
    void the_old_password_is_replaced() throws Exception {
        givenStaff("jane");

        mockMvc.perform(changePassword("jane", PASSWORD, NEW_PASSWORD)).andExpect(status().isOk());

        mockMvc.perform(loginAs("jane", PASSWORD)).andExpect(status().isUnauthorized());
        mockMvc.perform(loginAs("jane", NEW_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the new password must actually be new")
    void the_password_must_change() throws Exception {
        givenStaff("jane");

        // The one password rule that cannot be an annotation, because it needs the stored hash. It matters
        // most on a forced rotation, where this would satisfy the requirement while defeating its purpose.
        mockMvc.perform(changePassword("jane", PASSWORD, PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH.PASSWORD_UNCHANGED"));
    }

    @Test
    @DisplayName("a short password is refused before anything else happens")
    void short_passwords_are_refused() throws Exception {
        givenStaff("jane");

        // Length, and deliberately no composition rules — those push people towards Password1! and forbid
        // the long passphrases that are actually stronger.
        mockMvc.perform(changePassword("jane", PASSWORD, "short"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(loginAs("jane", PASSWORD)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a wrong current password is refused exactly as sign-in refuses one")
    void a_wrong_current_password_is_indistinguishable() throws Exception {
        givenStaff("jane");

        mockMvc.perform(changePassword("jane", "wrong-password", NEW_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.INVALID_CREDENTIALS"));

        // An unknown identifier answers identically. Anything else makes this route an account-existence
        // oracle sitting beside the sign-in endpoint that carefully is not one.
        mockMvc.perform(changePassword("nobody", "wrong-password", NEW_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("this route counts failures and locks, like the endpoint it sits beside")
    void the_lockout_is_not_bypassed() throws Exception {
        givenStaff("jane");

        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(changePassword("jane", "wrong-" + attempt, NEW_PASSWORD));
        }

        // THE WHOLE POINT. Without the counter here — and without noRollbackFor keeping it — this is a
        // password-guessing oracle with the lockout bypassed, directly beside the endpoint it protects.
        assertThat(failedAttempts("jane")).isEqualTo(5);
        mockMvc.perform(changePassword("jane", PASSWORD, NEW_PASSWORD))
                .andExpect(status().isLocked());
        mockMvc.perform(loginAs("jane", PASSWORD)).andExpect(status().isLocked());
    }

    // ─────────────────────────── issuing and redeeming ───────────────────────────

    @Test
    @DisplayName("a token works once and only once")
    void a_token_is_single_use() throws Exception {
        String adminToken = platformAdmin("admin");
        givenStaff("jane");
        String token = issueResetFor("jane", adminToken);

        mockMvc.perform(redeem(token, NEW_PASSWORD)).andExpect(status().isOk());

        // Replay must fail, or a token intercepted after use is still a working credential.
        mockMvc.perform(redeem(token, "yet-another-passphrase"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH.RESET_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("issuing a second token kills the first")
    void reissuing_supersedes() throws Exception {
        String adminToken = platformAdmin("admin");
        givenStaff("jane");

        String first = issueResetFor("jane", adminToken);
        String second = issueResetFor("jane", adminToken);

        // An administrator reissuing because "the link did not arrive" must not leave two live credentials
        // for one account, with the one that went astray working until it expires.
        mockMvc.perform(redeem(first, NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH.RESET_TOKEN_INVALID"));
        mockMvc.perform(redeem(second, NEW_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an expired token is refused, and says nothing about why")
    void expiry_is_enforced_and_indistinguishable() throws Exception {
        String adminToken = platformAdmin("admin");
        givenStaff("jane");
        String token = issueResetFor("jane", adminToken);

        // 'AT TIME ZONE UTC' is load-bearing. The JDBC driver sets the Postgres session timezone from the
        // JVM's, so a bare now() returns LOCAL wall-clock time — while the application writes and reads
        // these columns as UTC (hibernate.jdbc.time_zone). On a machine at UTC+3 a bare now() would put the
        // expiry three hours in the FUTURE and this test would pass an expired token.
        jdbc.update("UPDATE staff_password_resets "
                + "SET expires_at = (now() AT TIME ZONE 'UTC') - interval '1 minute'");

        // Same code as an unknown token. "Expired" would tell a guesser they had found a real one and
        // should look for a fresher one; the trail keeps the distinction, the caller does not get it.
        mockMvc.perform(redeem(token, NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH.RESET_TOKEN_INVALID"));

        mockMvc.perform(redeem("a-token-that-never-existed", NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH.RESET_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("the token is never stored, so the database cannot give it back")
    void only_a_fingerprint_is_stored() throws Exception {
        String adminToken = platformAdmin("admin");
        givenStaff("jane");
        String token = issueResetFor("jane", adminToken);

        String stored = jdbc.queryForObject(
                "SELECT token_hash FROM staff_password_resets", String.class);

        // A reset token is a bearer credential: whoever holds it can take the account. Read access to this
        // table must not be equivalent to the password of every account with a live reset.
        assertThat(stored).isNotEqualTo(token).hasSize(64);
    }

    @Test
    @DisplayName("a reset restores a locked-out account")
    void a_reset_clears_a_lockout() throws Exception {
        String adminToken = platformAdmin("admin");
        givenStaff("jane");
        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(loginAs("jane", "wrong-" + attempt));
        }
        mockMvc.perform(loginAs("jane", PASSWORD)).andExpect(status().isLocked());

        String token = issueResetFor("jane", adminToken);
        mockMvc.perform(redeem(token, NEW_PASSWORD)).andExpect(status().isOk());

        // The holder proved possession of the token. Making them wait out a lockout they may not have
        // caused would leave the account unusable for no security gain.
        mockMvc.perform(loginAs("jane", NEW_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a reset does not lift a suspension")
    void a_reset_is_not_a_restore() throws Exception {
        String adminToken = platformAdmin("admin");
        givenStaff("jane");
        jdbc.update("UPDATE staff_identities SET status = 'SUSPENDED' WHERE username = 'jane'");

        String token = issueResetFor("jane", adminToken);
        mockMvc.perform(redeem(token, NEW_PASSWORD)).andExpect(status().isOk());

        // Otherwise STAFF.PASSWORD_RESET would quietly confer STAFF.RESTORE. Only PENDING is ended by
        // setting a password, because PENDING is the status that means "no password yet".
        assertThat(statusOf("jane")).isEqualTo("SUSPENDED");
        mockMvc.perform(loginAs("jane", NEW_PASSWORD)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the root account's password cannot be reset through the API")
    void root_is_not_resettable() throws Exception {
        String adminToken = platformAdmin("admin");
        UUID root = jdbc.queryForObject(
                "SELECT uid FROM staff_identities WHERE tenancy = 'ROOT'", UUID.class);

        // Otherwise anyone holding this permission could take the break-glass identity — the one account
        // whose compromise has no recovery path. Its password comes from configuration and nowhere else.
        mockMvc.perform(post("/admin/v1/staff/uid/" + root + "/password-reset")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH.STAFF_NOT_MUTABLE"));
    }

    @Test
    @DisplayName("issuing a reset needs its own permission, not merely staff administration")
    void resetting_is_a_larger_power_than_administering() throws Exception {
        givenStaff("support");
        givenRole("support", "SUPPORT");
        givenStaff("jane");

        // SUPPORT holds STAFF.READ and not STAFF.PASSWORD_RESET, because being able to read a colleague's
        // account and being able to take it over are not the same power.
        mockMvc.perform(post("/admin/v1/staff/uid/" + staffUid("jane") + "/password-reset")
                .header("Authorization", "Bearer " + tokenFor("support")))
                .andExpect(status().isForbidden());
    }

    // ───────────────────────────────── the trail ─────────────────────────────────

    @Test
    @DisplayName("issuing, redeeming and rejecting a token are all recorded")
    void the_credential_path_is_recorded() throws Exception {
        String adminToken = platformAdmin("admin");
        givenStaff("jane");

        String token = issueResetFor("jane", adminToken);
        mockMvc.perform(redeem("not-a-real-token", NEW_PASSWORD));
        mockMvc.perform(redeem(token, NEW_PASSWORD)).andExpect(status().isOk());
        mockMvc.perform(changePassword("jane", NEW_PASSWORD, "one-more-long-passphrase"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT event_type FROM auth_audit_events", String.class))
                .contains("PASSWORD_RESET_ISSUED", "PASSWORD_RESET_REJECTED",
                        "PASSWORD_RESET_REDEEMED", "PASSWORD_CHANGED");

        // Who caused a reset to exist is where a takeover investigation starts, so it is recorded on the
        // row and not only in the log.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM staff_password_resets WHERE issued_by_uid = ?",
                Integer.class, staffUid("admin"))).isEqualTo(1);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private String issueResetFor(String username, String adminToken) throws Exception {
        String body = mockMvc.perform(post("/admin/v1/staff/uid/" + staffUid(username) + "/password-reset")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private RequestBuilder redeem(String token, String newPassword) {
        return post("/admin/v1/auth/password/redeem")
                .contentType("application/json")
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}");
    }

    private RequestBuilder changePassword(String identifier, String current, String replacement) {
        return post("/admin/v1/auth/password")
                .contentType("application/json")
                .content("{\"identifier\":\"" + identifier + "\",\"currentPassword\":\"" + current
                        + "\",\"newPassword\":\"" + replacement + "\"}");
    }

    private RequestBuilder loginAs(String identifier, String password) {
        return post("/admin/v1/auth/login")
                .contentType("application/json")
                .content("{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}");
    }

    private String platformAdmin(String username) throws Exception {
        givenStaff(username);
        givenRole(username, "PLATFORM_ADMIN");
        return tokenFor(username);
    }

    private String tokenFor(String username) throws Exception {
        return mockMvc.perform(loginAs(username, PASSWORD))
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

    private UUID staffUid(String username) {
        return jdbc.queryForObject("SELECT uid FROM staff_identities WHERE username = ?", UUID.class, username);
    }

    private String statusOf(String username) {
        return jdbc.queryForObject(
                "SELECT status FROM staff_identities WHERE username = ?", String.class, username);
    }

    private Integer failedAttempts(String username) {
        return jdbc.queryForObject("SELECT c.failed_attempts FROM staff_credentials c "
                + "JOIN staff_identities i ON i.id = c.staff_identity_id WHERE i.username = ?",
                Integer.class, username);
    }
}
