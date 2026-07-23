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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Provisioning and administering staff accounts.
 *
 * <p>The permission gate is covered by {@code RbacIntegrationTest}; what is tested here is everything a
 * permission <b>cannot</b> express — the limits that depend on the target as well as the actor. Those are
 * the ones that fail open when they are missing, because the caller does hold the code and the route does
 * let them through.
 */
@SpringBootTest(properties = "identity.bootstrap.root.password=bootstrap-secret-for-tests")
@AutoConfigureMockMvc
@Testcontainers
class StaffAdminIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String PASSWORD = "correct-horse-battery-staple";

    /** Two companies, so "the other company" is a real place rather than a hypothetical. */
    private static final UUID COMPANY_A = UUID.randomUUID();
    private static final UUID COMPANY_B = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM auth_audit_events");
        jdbc.update("DELETE FROM staff_operators");
        jdbc.update("DELETE FROM staff_roles WHERE staff_identity_id IN "
                + "(SELECT id FROM staff_identities WHERE tenancy <> 'ROOT')");
        jdbc.update("DELETE FROM staff_credentials WHERE staff_identity_id IN "
                + "(SELECT id FROM staff_identities WHERE tenancy <> 'ROOT')");
        jdbc.update("DELETE FROM staff_identities WHERE tenancy <> 'ROOT'");
    }

    // ─────────────────────────────── provisioning ───────────────────────────────

    @Test
    @DisplayName("a platform administrator creates an account, and it cannot sign in yet")
    void created_accounts_start_pending() throws Exception {
        String token = platformAdmin("admin");

        mockMvc.perform(createRequest(token, """
                {"username":"nova","email":"nova@bus-core.local","displayName":"Nova","tenancy":"ADMIN"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.uid").exists());

        // No credential row exists, so there is no password for the creator to know. Provisioning an
        // account and knowing its password are separate powers and this is what keeps them separate.
        assertThat(credentialCount("nova")).isZero();
        mockMvc.perform(loginAs("nova", PASSWORD)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the envelope's status is the response's status")
    void created_is_actually_201() throws Exception {
        String token = platformAdmin("admin");

        // A create returning 200 in the transport and 201 in the body would have the envelope contradicting
        // the thing it derives `success` from. The advice syncs them; without it this passes on the body
        // assertion alone and nobody notices.
        mockMvc.perform(createRequest(token, """
                {"username":"nova","email":"nova@bus-core.local","displayName":"Nova","tenancy":"ADMIN"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("nobody creates a second root, not even root")
    void root_cannot_be_created() throws Exception {
        String token = platformAdmin("admin");

        // The partial unique index would refuse it anyway. Refusing here means the caller gets an
        // explanation instead of a constraint violation.
        mockMvc.perform(createRequest(token, """
                {"username":"root2","email":"root2@bus-core.local","displayName":"Root","tenancy":"ROOT"}
                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH.TENANCY_NOT_PERMITTED"));
    }

    @Test
    @DisplayName("platform staff must name the company an operator account belongs to")
    void operator_accounts_need_a_company() throws Exception {
        String token = platformAdmin("admin");

        // 400, not 403 — nothing is refused, the request is incomplete. The widest caller is the one who
        // has to be specific, because they belong to no company and the answer is genuinely undetermined.
        mockMvc.perform(createRequest(token, """
                {"username":"dep","email":"dep@bus-core.local","displayName":"Dep","tenancy":"OPERATOR"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH.COMPANY_REQUIRED"));
    }

    @Test
    @DisplayName("a taken username is refused with something the administrator can act on")
    void duplicate_usernames_are_refused() throws Exception {
        String token = platformAdmin("admin");
        givenStaff("nova", "ADMIN", null);

        mockMvc.perform(createRequest(token, """
                {"username":"NOVA","email":"other@bus-core.local","displayName":"Nova","tenancy":"ADMIN"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH.STAFF_ALREADY_EXISTS"));
    }

    // ──────────────────────── the escalation guard on create ────────────────────────

    @Test
    @DisplayName("an operator administrator cannot mint a platform account")
    void operator_admins_cannot_create_platform_staff() throws Exception {
        String token = operatorAdmin("dora", COMPANY_A);

        // THE ESCALATION GUARD. Dora holds STAFF.CREATE, so the permission gate lets her through and only
        // this check stands between her and an account more powerful than her own.
        mockMvc.perform(createRequest(token, """
                {"username":"sneak","email":"sneak@bus-core.local","displayName":"Sneak","tenancy":"ADMIN"}
                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH.TENANCY_NOT_PERMITTED"));
    }

    @Test
    @DisplayName("an operator administrator's new accounts land in their own company, whatever the body says")
    void the_body_cannot_choose_the_company() throws Exception {
        String token = operatorAdmin("dora", COMPANY_A);

        mockMvc.perform(createRequest(token, """
                {"username":"colleague","email":"colleague@bus-core.local","displayName":"Colleague",
                 "tenancy":"OPERATOR","companyUid":"%s"}
                """.formatted(COMPANY_B)))
                .andExpect(status().isCreated());

        // The request named company B and the account is in company A. A body that could choose the company
        // would be an authority field, and authority never comes from a body.
        assertThat(companyOf("colleague")).isEqualTo(COMPANY_A);
    }

    // ─────────────────────────── the company boundary ───────────────────────────

    @Test
    @DisplayName("an operator administrator lists their own company and nobody else's")
    void listing_is_bounded_by_company() throws Exception {
        givenStaff("mine", "OPERATOR", COMPANY_A);
        givenStaff("theirs", "OPERATOR", COMPANY_B);
        givenStaff("platform", "ADMIN", null);
        String token = operatorAdmin("dora", COMPANY_A);

        String body = mockMvc.perform(get("/admin/v1/staff").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Filtered in the query rather than afterwards — the distinction is invisible here and decides
        // whether the totals are right the moment this endpoint is paged.
        assertThat(body).contains("mine").contains("dora");
        assertThat(body).doesNotContain("theirs").doesNotContain("platform");
    }

    @Test
    @DisplayName("a platform administrator lists everyone")
    void platform_staff_see_every_company() throws Exception {
        givenStaff("mine", "OPERATOR", COMPANY_A);
        givenStaff("theirs", "OPERATOR", COMPANY_B);
        String token = platformAdmin("admin");

        String body = mockMvc.perform(get("/admin/v1/staff").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("mine").contains("theirs");
    }

    @Test
    @DisplayName("another company's account is 404, not 403")
    void cross_company_reads_do_not_confirm_existence() throws Exception {
        givenStaff("theirs", "OPERATOR", COMPANY_B);
        String token = operatorAdmin("dora", COMPANY_A);

        // The one place a refusal here is deliberately vague. 403 would confirm that the uid names a real
        // account in a company the caller cannot see, and repeated probing turns that difference into a
        // directory of another business's staff.
        mockMvc.perform(get("/admin/v1/staff/uid/" + staffUid("theirs"))
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUTH.STAFF_NOT_FOUND"));
    }

    @Test
    @DisplayName("another company's account cannot be suspended either")
    void cross_company_writes_are_refused() throws Exception {
        givenStaff("theirs", "OPERATOR", COMPANY_B);
        String token = operatorAdmin("dora", COMPANY_A);

        mockMvc.perform(suspendRequest(staffUid("theirs"), token, "SUSPENDED"))
                .andExpect(status().isNotFound());

        assertThat(statusOf("theirs")).isEqualTo("ACTIVE");
    }

    // ─────────────────────────────── withdrawal ───────────────────────────────

    @Test
    @DisplayName("suspending an account stops it signing in, and restoring it lets it back")
    void suspend_and_restore_round_trip() throws Exception {
        String token = platformAdmin("admin");
        givenStaff("nova", "ADMIN", null);
        UUID nova = staffUid("nova");

        mockMvc.perform(loginAs("nova", PASSWORD)).andExpect(status().isOk());

        mockMvc.perform(suspendRequest(nova, token, "SUSPENDED")).andExpect(status().isOk());
        mockMvc.perform(loginAs("nova", PASSWORD)).andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/admin/v1/staff/uid/" + nova + "/suspension")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(loginAs("nova", PASSWORD)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the root account cannot be suspended")
    void root_cannot_be_suspended() throws Exception {
        String token = platformAdmin("admin");
        UUID root = jdbc.queryForObject(
                "SELECT uid FROM staff_identities WHERE tenancy = 'ROOT'", UUID.class);

        // The break-glass identity is what rescues the system when administration itself is broken.
        // Suspending it is the one action that turns a recoverable incident into an unrecoverable one.
        mockMvc.perform(suspendRequest(root, token, "SUSPENDED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH.STAFF_NOT_MUTABLE"));
    }

    @Test
    @DisplayName("an administrator cannot withdraw their own access")
    void self_suspension_is_refused() throws Exception {
        String token = platformAdmin("admin");

        // Not paternalism: undoing it needs a session they would no longer have.
        mockMvc.perform(suspendRequest(staffUid("admin"), token, "SUSPENDED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH.STAFF_NOT_MUTABLE"));
    }

    @Test
    @DisplayName("the suspension route cannot be used to make an account active")
    void active_is_not_a_withdrawal() throws Exception {
        String token = platformAdmin("admin");
        givenStaff("nova", "ADMIN", null);

        // Otherwise STAFF.SUSPEND would quietly confer STAFF.RESTORE, and splitting them would be theatre.
        mockMvc.perform(suspendRequest(staffUid("nova"), token, "ACTIVE"))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────── memberships ───────────────────────────────

    @Test
    @DisplayName("linking an operator widens what that account reaches, and is idempotent")
    void linking_is_idempotent() throws Exception {
        String token = platformAdmin("admin");
        givenStaff("driver", "OPERATOR", COMPANY_A);
        UUID driver = staffUid("driver");
        UUID operator = UUID.randomUUID();

        mockMvc.perform(linkRequest(driver, operator, token)).andExpect(status().isOk());
        mockMvc.perform(linkRequest(driver, operator, token)).andExpect(status().isOk());

        // A retried request must not be an error the caller can do nothing about — and the unique index on
        // the pair is what makes one row rather than two, so unlinking removes the membership rather than
        // half of it.
        assertThat(membershipCount("driver")).isEqualTo(1);

        mockMvc.perform(delete("/admin/v1/staff/uid/" + driver + "/operators/" + operator)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assertThat(membershipCount("driver")).isZero();
    }

    @Test
    @DisplayName("platform staff hold no memberships")
    void platform_staff_cannot_be_linked() throws Exception {
        String token = platformAdmin("admin");
        givenStaff("nova", "ADMIN", null);

        // They already reach every operator, so a membership would be redundant or a contradiction. The
        // composite foreign key refuses it too; this says why instead of surfacing a constraint violation.
        mockMvc.perform(linkRequest(staffUid("nova"), UUID.randomUUID(), token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH.TENANCY_NOT_PERMITTED"));
    }

    @Test
    @DisplayName("an administrator cannot hand out reach they do not have")
    void linking_cannot_exceed_the_linker() throws Exception {
        UUID served = UUID.randomUUID();
        givenStaff("colleague", "OPERATOR", COMPANY_A);
        String token = operatorAdmin("dora", COMPANY_A, served);

        mockMvc.perform(linkRequest(staffUid("colleague"), served, token)).andExpect(status().isOk());

        // THE ESCALATION GUARD ON MEMBERSHIPS. Dora serves one operator; attaching a second would give a
        // colleague reach she does not have herself, which is a privilege escalation performed entirely
        // through permitted operations.
        mockMvc.perform(linkRequest(staffUid("colleague"), UUID.randomUUID(), token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SCOPE.NOT_AUTHORISED"));

        assertThat(membershipCount("colleague")).isEqualTo(1);
    }

    @Test
    @DisplayName("memberships are readable, so an administrator can see whose data an account reaches")
    void memberships_are_listed() throws Exception {
        String token = platformAdmin("admin");
        givenStaff("driver", "OPERATOR", COMPANY_A);
        UUID operator = UUID.randomUUID();
        mockMvc.perform(linkRequest(staffUid("driver"), operator, token));

        mockMvc.perform(get("/admin/v1/staff/uid/" + staffUid("driver") + "/operators")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value(operator.toString()));
    }

    // ───────────────────────────────── the trail ─────────────────────────────────

    @Test
    @DisplayName("provisioning and access changes are all recorded")
    void administration_is_recorded() throws Exception {
        String token = platformAdmin("admin");
        givenStaff("driver", "OPERATOR", COMPANY_A);
        UUID driver = staffUid("driver");
        UUID operator = UUID.randomUUID();

        mockMvc.perform(createRequest(token, """
                {"username":"nova","email":"nova@bus-core.local","displayName":"Nova","tenancy":"ADMIN"}
                """));
        mockMvc.perform(suspendRequest(driver, token, "BLOCKED"));
        mockMvc.perform(linkRequest(driver, operator, token));
        mockMvc.perform(delete("/admin/v1/staff/uid/" + driver + "/operators/" + operator)
                .header("Authorization", "Bearer " + token));

        // Two people holding identical roles can reach entirely different rows. A membership change is the
        // only record of why, so it belongs in the trail as much as a role grant does.
        assertThat(eventTypes()).contains("STAFF_CREATED", "STAFF_SUSPENDED",
                "OPERATOR_LINKED", "OPERATOR_UNLINKED");
    }

    @Test
    @DisplayName("the withdrawal's reason is kept, because an account has a history of them")
    void the_reason_reaches_the_trail() throws Exception {
        String token = platformAdmin("admin");
        givenStaff("nova", "ADMIN", null);

        mockMvc.perform(post("/admin/v1/staff/uid/" + staffUid("nova") + "/suspension")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"status\":\"SUSPENDED\",\"reason\":\"under investigation\"}"))
                .andExpect(status().isOk());

        String detail = jdbc.queryForObject(
                "SELECT identifier_used FROM auth_audit_events WHERE event_type = 'STAFF_SUSPENDED'",
                String.class);

        // On the account it would be one column holding only the most recent explanation; in the trail it
        // is one entry per withdrawal, which is the question anyone actually asks afterwards.
        assertThat(detail).contains("SUSPENDED").contains("under investigation");
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private RequestBuilder createRequest(String token, String body) {
        return post("/admin/v1/staff")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json").content(body);
    }

    private RequestBuilder suspendRequest(UUID staffUid, String token, String status) {
        return post("/admin/v1/staff/uid/" + staffUid + "/suspension")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"status\":\"" + status + "\"}");
    }

    private RequestBuilder linkRequest(UUID staffUid, UUID operatorUid, String token) {
        return post("/admin/v1/staff/uid/" + staffUid + "/operators/" + operatorUid)
                .header("Authorization", "Bearer " + token);
    }

    private RequestBuilder loginAs(String identifier, String password) {
        return post("/admin/v1/auth/login")
                .contentType("application/json")
                .content("{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}");
    }

    /** A signed-in platform administrator holding every staff-administration code. */
    private String platformAdmin(String username) throws Exception {
        givenStaff(username, "ADMIN", null);
        givenRole(username, "PLATFORM_ADMIN");
        return tokenFor(username);
    }

    /** A signed-in operator administrator, optionally already serving some operators. */
    private String operatorAdmin(String username, UUID companyUid, UUID... served) throws Exception {
        givenStaff(username, "OPERATOR", companyUid);
        givenRole(username, "OPERATOR_ADMIN");
        for (UUID operatorUid : served) {
            jdbc.update("""
                    INSERT INTO staff_operators (uid, staff_identity_id, operator_uid, company_uid,
                                                 created_at, updated_at)
                    VALUES (?, (SELECT id FROM staff_identities WHERE username = ?), ?, ?, now(), now())
                    """, UUID.randomUUID(), username, operatorUid, companyUid);
        }
        return tokenFor(username);
    }

    private String tokenFor(String username) throws Exception {
        return mockMvc.perform(loginAs(username, PASSWORD))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private void givenStaff(String username, String tenancy, UUID companyUid) {
        jdbc.update("""
                INSERT INTO staff_identities (uid, username, email, display_name, tenancy, status,
                                              company_uid, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, now(), now())
                """, UUID.randomUUID(), username, username + "@bus-core.local", username, tenancy,
                companyUid);
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

    private UUID companyOf(String username) {
        return jdbc.queryForObject(
                "SELECT company_uid FROM staff_identities WHERE username = ?", UUID.class, username);
    }

    private String statusOf(String username) {
        return jdbc.queryForObject(
                "SELECT status FROM staff_identities WHERE username = ?", String.class, username);
    }

    private Integer credentialCount(String username) {
        return jdbc.queryForObject("SELECT count(*) FROM staff_credentials c JOIN staff_identities i "
                + "ON i.id = c.staff_identity_id WHERE i.username = ?", Integer.class, username);
    }

    private Integer membershipCount(String username) {
        return jdbc.queryForObject("SELECT count(*) FROM staff_operators so JOIN staff_identities i "
                + "ON i.id = so.staff_identity_id WHERE i.username = ?", Integer.class, username);
    }

    private List<String> eventTypes() {
        return jdbc.queryForList("SELECT event_type FROM auth_audit_events", String.class);
    }
}
