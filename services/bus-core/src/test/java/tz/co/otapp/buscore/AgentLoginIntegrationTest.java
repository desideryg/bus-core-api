package tz.co.otapp.buscore;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tz.co.otapp.buscore.identityaccess.internal.security.PinEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent sign-in — and the moment the audience gate stops being a no-op.
 *
 * <p>Slice 3 built the gate while every caller was staff, so it could only be tested one-sidedly. A second
 * principal type finally makes the interesting direction testable: <b>a staff token on an agent route</b>,
 * which nothing else in the system refuses.
 */
@SpringBootTest(properties = "identity.bootstrap.root.password=bootstrap-secret-for-tests")
@AutoConfigureMockMvc
@Testcontainers
class AgentLoginIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String PIN = "624913";
    private static final String STAFF_PASSWORD = "correct-horse-battery-staple";

    /** One handset, three spellings. All of them are this agent. */
    private static final String CANONICAL = "+255712345678";
    private static final String NATIONAL = "0712345678";
    private static final String NO_PLUS = "255712345678";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PinEncoder pinEncoder;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM auth_audit_events");
        jdbc.update("DELETE FROM agent_credentials");
        jdbc.update("DELETE FROM agent_identities");
        jdbc.update("DELETE FROM staff_roles WHERE staff_identity_id IN "
                + "(SELECT id FROM staff_identities WHERE tenancy <> 'ROOT')");
        jdbc.update("DELETE FROM staff_credentials WHERE staff_identity_id IN "
                + "(SELECT id FROM staff_identities WHERE tenancy <> 'ROOT')");
        jdbc.update("DELETE FROM staff_identities WHERE tenancy <> 'ROOT'");
    }

    // ─────────────────────────────── signing in ───────────────────────────────

    @Test
    @DisplayName("an agent signs in with a phone number and a PIN")
    void an_agent_can_sign_in() throws Exception {
        givenAgent(CANONICAL, "ACTIVE");

        mockMvc.perform(login(CANONICAL, PIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.displayName").value("Agent " + CANONICAL));
    }

    @Test
    @DisplayName("however the number is typed, it is the same agent")
    void the_number_is_canonicalised() throws Exception {
        givenAgent(CANONICAL, "ACTIVE");

        // One handset, and a person who signed up one way will type it another. Without canonicalisation
        // this is three accounts on a good day and "your PIN is wrong, forever" on a normal one.
        mockMvc.perform(login(NATIONAL, PIN)).andExpect(status().isOk());
        mockMvc.perform(login(NO_PLUS, PIN)).andExpect(status().isOk());
        mockMvc.perform(login("0712 345 678", PIN)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a non-canonical row cannot be written, so it cannot become a login nobody can use")
    void the_database_refuses_a_non_canonical_number() {
        // Java canonicalises on the way in; this is what makes that a guarantee rather than a habit. A row
        // written past the normaliser would be unreachable — every sign-in canonicalises before looking up
        // — and nothing anywhere would report it.
        assertThatThrownBy(() -> insertIdentity(NATIONAL, "ACTIVE"))
                .isInstanceOf(DataAccessException.class);
    }

    // ─────────────────────── the number is never an oracle ───────────────────────

    @Test
    @DisplayName("an unknown number, a malformed one and a wrong PIN are one answer")
    void refusals_are_indistinguishable() throws Exception {
        givenAgent(CANONICAL, "ACTIVE");

        // A staff username has to be guessed; a phone number is dialled. If these answered differently, any
        // block of numbers becomes a directory of who sells tickets — and those people carry float.
        mockMvc.perform(login(CANONICAL, "000000"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.INVALID_CREDENTIALS"));

        mockMvc.perform(login("+255799999999", PIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.INVALID_CREDENTIALS"));

        // Malformed is the one most likely to be reported helpfully, and it must not be: "that is not a
        // valid number" tells a caller which of their guesses were even worth making.
        mockMvc.perform(login("not-a-number", PIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("a suspended agent is indistinguishable from a wrong PIN")
    void status_is_checked_after_the_pin() throws Exception {
        givenAgent(CANONICAL, "SUSPENDED");

        mockMvc.perform(login(CANONICAL, PIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("every attempt is recorded, including ones naming nobody")
    void attempts_reach_the_trail() throws Exception {
        givenAgent(CANONICAL, "ACTIVE");

        mockMvc.perform(login(CANONICAL, PIN));
        mockMvc.perform(login("+255700000001", PIN));
        mockMvc.perform(login("+255700000002", PIN));

        // A run of failures across a block of numbers is what enumeration looks like, and it is invisible
        // if only resolved accounts are recorded.
        assertThat(eventsOfType("LOGIN_SUCCESS")).hasSize(1);
        assertThat(jdbc.queryForList(
                "SELECT principal_uid FROM auth_audit_events WHERE event_type = 'LOGIN_FAILURE'"))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.get("principal_uid")).isNull());

        // Recorded as an AGENT, which is why the trail carries a principal type at all.
        assertThat(jdbc.queryForObject(
                "SELECT principal_type FROM auth_audit_events WHERE event_type = 'LOGIN_SUCCESS'",
                String.class)).isEqualTo("AGENT");
    }

    // ───────────────────────────── the lockout ─────────────────────────────

    @Test
    @DisplayName("three failures lock an agent, where five lock a staff member")
    void the_lockout_is_harsher_than_for_a_password() throws Exception {
        givenAgent(CANONICAL, "ACTIVE");

        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(login(CANONICAL, "00000" + attempt)).andExpect(status().isUnauthorized());
        }

        // A shorter secret must buy fewer guesses. At 5-per-15-minutes a 4-digit PIN falls in about three
        // weeks of unattended guessing; at 3-per-30-minutes that becomes about ten.
        mockMvc.perform(login(CANONICAL, PIN)).andExpect(status().isLocked());
        assertThat(eventsOfType("ACCOUNT_LOCKED")).hasSize(1);
        assertThat(failedAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("the failure counter survives the rejection that follows it")
    void the_counter_is_not_rolled_back() throws Exception {
        givenAgent(CANONICAL, "ACTIVE");

        mockMvc.perform(login(CANONICAL, "111111"));

        // Without noRollbackFor the increment is discarded along with the throw, the lockout never fires,
        // and NOTHING ANYWHERE FAILS — the defence is simply absent. On a six-digit secret that is the
        // difference between unguessable and guessable.
        assertThat(failedAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("a correct PIN clears the counter, so failures must be consecutive")
    void success_clears_the_counter() throws Exception {
        givenAgent(CANONICAL, "ACTIVE");

        mockMvc.perform(login(CANONICAL, "111111"));
        mockMvc.perform(login(CANONICAL, PIN)).andExpect(status().isOk());

        assertThat(failedAttempts()).isZero();
    }

    // ───────────────────────── the PIN is peppered ─────────────────────────

    @Test
    @DisplayName("the stored hash cannot be tested without the pepper")
    void the_hash_alone_is_not_enough() throws Exception {
        givenAgent(CANONICAL, "ACTIVE");
        String stored = jdbc.queryForObject("SELECT pin_hash FROM agent_credentials", String.class);

        // THE POINT OF THE PEPPER. A six-digit PIN is a million candidates: an attacker holding this column
        // walks the whole space offline whatever the work factor. Peppering means the plain encoder — all
        // anyone has without the configuration — cannot confirm even the correct PIN.
        assertThat(passwordEncoder.matches(PIN, stored)).isFalse();
        assertThat(pinEncoder.matches(PIN, stored)).isTrue();
    }

    // ──────────────────── the audience gate, finally two-sided ────────────────────

    @Test
    @DisplayName("an agent token is refused on the staff surface")
    void an_agent_cannot_reach_the_staff_surface() throws Exception {
        givenAgent(CANONICAL, "ACTIVE");
        String agentToken = agentToken();

        mockMvc.perform(get("/admin/v1/auth/me").header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH.AUDIENCE_MISMATCH"));
    }

    @Test
    @DisplayName("a staff token is refused on the agent surface, and only this gate refuses it")
    void a_staff_member_cannot_reach_the_agent_surface() throws Exception {
        givenStaff("jane");
        String staffToken = staffToken("jane");

        // THE DIRECTION NOTHING ELSE COVERS. An agent is kept off the staff surface by an empty permission
        // set as well, so that half would pass even with the gate deleted. A staff member on an agent route
        // is refused by this line and by nothing else in the system.
        //
        // The login route is used because it is the only agent route this slice ships. It is a public path,
        // which makes the test sharper rather than weaker: the chain would let this request through, and
        // the refusal comes purely from the gate reading a STAFF principal on an AGENT prefix.
        mockMvc.perform(login(CANONICAL, PIN).header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH.AUDIENCE_MISMATCH"));
    }

    @Test
    @DisplayName("an agent holds no permissions, and a token claiming otherwise is unusable")
    void a_forged_agent_token_is_not_a_privileged_one() throws Exception {
        givenAgent(CANONICAL, "ACTIVE");

        // Constructing the principal directly is the closest a test can get to a forged token: it is
        // exactly what JwtService#parse does with the claims it reads. Because parse catches
        // IllegalArgumentException and returns empty, a token asserting AGENT with permissions is not a
        // privileged principal — it is an unusable credential, refused like a bad signature.
        assertThatThrownBy(() -> new tz.co.otapp.buscore.identityaccess.Principal(
                UUID.randomUUID(),
                tz.co.otapp.buscore.identityaccess.PrincipalType.AGENT,
                null, List.of(), java.util.Set.of("ROLE.GRANT")))
                .isInstanceOf(IllegalArgumentException.class);

        // A real agent token carries none, which is why the check above can be absolute.
        mockMvc.perform(login(CANONICAL, PIN)).andExpect(status().isOk());
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private MockHttpServletRequestBuilder login(String msisdn, String pin) {
        return post("/agent/v1/auth/login")
                .contentType("application/json")
                .content("{\"msisdn\":\"" + msisdn + "\",\"pin\":\"" + pin + "\"}");
    }

    private String agentToken() throws Exception {
        return mockMvc.perform(login(CANONICAL, PIN))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private String staffToken(String username) throws Exception {
        return mockMvc.perform(post("/admin/v1/auth/login")
                .contentType("application/json")
                .content("{\"identifier\":\"" + username + "\",\"password\":\"" + STAFF_PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private void givenAgent(String canonicalMsisdn, String status) {
        insertIdentity(canonicalMsisdn, status);
        jdbc.update("""
                INSERT INTO agent_credentials (uid, agent_identity_id, pin_hash, pin_updated_at,
                                               failed_attempts, must_change_pin, created_at, updated_at)
                VALUES (?, (SELECT id FROM agent_identities WHERE msisdn = ?), ?, now(), 0, false,
                        now(), now())
                """, UUID.randomUUID(), canonicalMsisdn, pinEncoder.encode(PIN));
    }

    private void insertIdentity(String msisdn, String status) {
        jdbc.update("""
                INSERT INTO agent_identities (uid, msisdn, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, UUID.randomUUID(), msisdn, "Agent " + msisdn, status);
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
                VALUES (?, (SELECT id FROM staff_identities WHERE username = ?), ?, now(), 0, false,
                        now(), now())
                """, UUID.randomUUID(), username, passwordEncoder.encode(STAFF_PASSWORD));
    }

    private List<?> eventsOfType(String type) {
        return jdbc.queryForList("SELECT * FROM auth_audit_events WHERE event_type = ?", type);
    }

    private Integer failedAttempts() {
        return jdbc.queryForObject("SELECT failed_attempts FROM agent_credentials", Integer.class);
    }
}
