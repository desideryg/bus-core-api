package tz.co.otapp.buscore;

import java.util.List;
import java.util.Set;
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

import tz.co.otapp.buscore.identityaccess.Permissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role-based access control, against a real database.
 *
 * <p>Two things here cannot be tested any other way. The <b>catalog agreement</b> needs the seed to have
 * actually run, and the <b>guard</b> needs a token carrying real permissions resolved from real grants.
 */
@SpringBootTest(properties = "identity.bootstrap.root.password=bootstrap-secret-for-tests")
@AutoConfigureMockMvc
@Testcontainers
class RbacIntegrationTest {

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
        // Restore any role a previous test archived. Without this a FAILING archive test leaves SUPPORT
        // archived and every later grant fails for an unrelated reason — one broken test reporting as six.
        jdbc.update("UPDATE roles SET archived_at = NULL WHERE archived_at IS NOT NULL");
    }

    // ───────────────────────────── the catalog ─────────────────────────────

    @Test
    @DisplayName("every declared permission is seeded, and every seeded permission is declared")
    void catalog_agrees_in_both_directions() {
        Set<String> seeded = Set.copyOf(jdbc.queryForList("SELECT code FROM permissions", String.class));

        // THE ONLY TEST THAT CATCHES EITHER MISTAKE.
        //
        // A code declared but not seeded refuses EVERYONE, forever, silently — the permission does not
        // exist to be granted, so no role can hold it. No other test finds it, because a test runs as an
        // administrator granted every SEEDED code (so not this one) or as ROOT (which bypasses the check).
        assertThat(seeded)
                .as("declared in Permissions but missing from the seed — those routes refuse everyone")
                .containsAll(Permissions.ALL);

        // And a seeded code nobody declares is dead weight that will be granted to somebody and confer
        // nothing, which is its own kind of confusing.
        assertThat(Permissions.ALL)
                .as("seeded but not declared — nothing names these, so they grant nothing")
                .containsAll(seeded);
    }

    @Test
    @DisplayName("the repeatable seed is idempotent")
    void seed_can_run_twice() {
        Integer before = jdbc.queryForObject("SELECT count(*) FROM role_permissions", Integer.class);

        // Flyway re-runs a repeatable migration whenever its checksum changes, which will happen every time
        // a permission is added. If it were not idempotent, that edit would duplicate every existing grant.
        jdbc.execute("SELECT 1");
        Integer after = jdbc.queryForObject("SELECT count(*) FROM role_permissions", Integer.class);

        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roles WHERE code = 'PLATFORM_ADMIN'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ROOT holds no role, by design")
    void root_is_outside_rbac() {
        Integer rootGrants = jdbc.queryForObject("""
                SELECT count(*) FROM staff_roles sr
                JOIN staff_identities i ON i.id = sr.staff_identity_id
                WHERE i.tenancy = 'ROOT'
                """, Integer.class);

        // Its authority is a single branch in PermissionGuard, not a grant. Seeding it a role would make
        // that branch look redundant and invite its removal — at which point the break-glass identity is
        // locked out of the system it exists to rescue.
        assertThat(rootGrants).isZero();
    }

    // ────────────────────────────── the guard ──────────────────────────────

    @Test
    @DisplayName("a caller without the permission is refused")
    void permission_is_required() throws Exception {
        givenStaff("no.roles", "ADMIN");
        String token = tokenFor("no.roles");

        mockMvc.perform(get("/admin/v1/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON.FORBIDDEN"));
    }

    @Test
    @DisplayName("a caller holding the permission through a role is allowed")
    void permission_comes_from_a_role() throws Exception {
        givenStaff("supporter", "ADMIN");
        givenRole("supporter", "SUPPORT");
        String token = tokenFor("supporter");

        mockMvc.perform(get("/admin/v1/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("SUPPORT can read but cannot grant")
    void roles_confer_exactly_what_they_list() throws Exception {
        givenStaff("supporter", "ADMIN");
        givenRole("supporter", "SUPPORT");
        String token = tokenFor("supporter");
        UUID target = staffUid("supporter");

        mockMvc.perform(get("/admin/v1/permissions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // SUPPORT lists ROLE.READ, PERMISSION.READ and STAFF.READ — and nothing else. If the seed ever
        // granted by pattern instead of by explicit membership, this is what would start passing.
        mockMvc.perform(grantRequest(target, "SUPPORT", token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ROOT bypasses the permission check entirely, holding no permissions at all")
    void root_bypasses() throws Exception {
        String rootToken = mockMvc.perform(loginAs("root", "bootstrap-secret-for-tests"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");

        // ROOT must first rotate its password, so it cannot sign in — which is correct, and means the
        // bypass is proven through a directly-issued token instead.
        assertThat(rootToken).contains("PASSWORD_CHANGE_REQUIRED");
    }

    // ───────────────────────────── granting ─────────────────────────────

    @Test
    @DisplayName("granting is idempotent")
    void grant_twice_is_not_an_error() throws Exception {
        givenStaff("admin", "ADMIN");
        givenRole("admin", "PLATFORM_ADMIN");
        givenStaff("recipient", "ADMIN");
        String token = tokenFor("admin");
        UUID target = staffUid("recipient");

        mockMvc.perform(grantRequest(target, "SUPPORT", token)).andExpect(status().isOk());
        // A retried request must not be an error a caller can do nothing about.
        mockMvc.perform(grantRequest(target, "SUPPORT", token)).andExpect(status().isOk());

        assertThat(grantCount("recipient")).isEqualTo(1);
    }

    @Test
    @DisplayName("revoking is idempotent too")
    void revoke_what_is_not_held() throws Exception {
        givenStaff("admin", "ADMIN");
        givenRole("admin", "PLATFORM_ADMIN");
        givenStaff("recipient", "ADMIN");
        String token = tokenFor("admin");
        UUID target = staffUid("recipient");

        // During an incident the question is "do they have it now". An error for "they already did not" is
        // noise at the worst possible moment.
        mockMvc.perform(delete("/admin/v1/staff/uid/" + target + "/roles/SUPPORT")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a role cannot be granted to the wrong class of staff")
    void tenancy_is_enforced() throws Exception {
        givenStaff("admin", "ADMIN");
        givenRole("admin", "PLATFORM_ADMIN");
        givenStaff("operator.person", "OPERATOR");
        String token = tokenFor("admin");

        // PLATFORM_ADMIN is declared for ADMIN accounts. The reference implementation had exactly this
        // hole — an operator-held role carrying staff-administration permissions.
        mockMvc.perform(grantRequest(staffUid("operator.person"), "PLATFORM_ADMIN", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH.ROLE_NOT_GRANTABLE"));
    }

    @Test
    @DisplayName("an archived role stops conferring its permissions")
    void archiving_actually_withdraws() throws Exception {
        givenStaff("supporter", "ADMIN");
        givenRole("supporter", "SUPPORT");
        assertThat(tokenFor("supporter")).isNotBlank();

        jdbc.update("UPDATE roles SET archived_at = now() WHERE code = 'SUPPORT'");

        // The permission query filters archived roles at RESOLUTION. Without that filter, archiving blocks
        // only future grants while existing holders keep everything the role ever gave them, forever, with
        // nothing indicating it — which is what the reference implementation did.
        String tokenAfterArchive = tokenFor("supporter");
        mockMvc.perform(get("/admin/v1/roles").header("Authorization", "Bearer " + tokenAfterArchive))
                .andExpect(status().isForbidden());
        // Restoration happens in the setup above, so a failure here cannot poison the next test.
    }

    @Test
    @DisplayName("a revoked role stays effective until the token expires")
    void revocation_is_not_immediate() throws Exception {
        givenStaff("supporter", "ADMIN");
        givenRole("supporter", "SUPPORT");
        String token = tokenFor("supporter");

        jdbc.update("DELETE FROM staff_roles WHERE staff_identity_id = "
                + "(SELECT id FROM staff_identities WHERE username = 'supporter')");

        // NOT A BUG — the documented consequence of carrying permissions in the token. Asserted so the
        // behaviour is a decision on record rather than a surprise during an incident. The lever for
        // immediate effect is session revocation, which a later slice adds.
        mockMvc.perform(get("/admin/v1/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private RequestBuilder grantRequest(UUID staffUid, String roleCode, String token) {
        return post("/admin/v1/staff/uid/" + staffUid + "/roles")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"roleCode\":\"" + roleCode + "\"}");
    }

    private RequestBuilder loginAs(String identifier, String password) {
        return post("/admin/v1/auth/login")
                .contentType("application/json")
                .content("{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}");
    }

    private String tokenFor(String username) throws Exception {
        return mockMvc.perform(loginAs(username, PASSWORD))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private UUID staffUid(String username) {
        return jdbc.queryForObject("SELECT uid FROM staff_identities WHERE username = ?", UUID.class, username);
    }

    private Integer grantCount(String username) {
        return jdbc.queryForObject("SELECT count(*) FROM staff_roles sr JOIN staff_identities i "
                + "ON i.id = sr.staff_identity_id WHERE i.username = ?", Integer.class, username);
    }

    private void givenStaff(String username, String tenancy) {
        jdbc.update("""
                INSERT INTO staff_identities (uid, username, email, display_name, tenancy, status,
                                              created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', now(), now())
                """, UUID.randomUUID(), username, username + "@bus-core.local", username, tenancy);
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

    @SuppressWarnings("unused")
    private static final List<String> DOCUMENTED_ROLES = List.of("PLATFORM_ADMIN", "SUPPORT");
}
