package tz.co.otapp.buscore.identityaccess.internal.api.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tz.co.otapp.buscore.apicontracts.response.ApiResponse;
import tz.co.otapp.buscore.identityaccess.Permissions;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.GrantRoleRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.PermissionView;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.RoleView;
import tz.co.otapp.buscore.identityaccess.internal.service.RoleAdministrationService;

/**
 * The role and permission administration surface.
 *
 * <p>The first gated routes in the system, and therefore the first proof that the guard works.
 *
 * <h2>Every expression concatenates a constant</h2>
 *
 * <pre>
 * &#64;PreAuthorize("&#64;perm.has('" + Permissions.ROLE_GRANT + "')")
 * </pre>
 *
 * <p>Never a string literal. SpEL is not compiled, so a typo inside the quotes is not an error — it is a
 * permission nobody holds, which means <b>the route silently refuses everyone forever</b> and no test
 * catches it, because a test runs as an administrator holding every real code or as ROOT which bypasses
 * the check. Concatenating the constant turns that into a compile failure.
 *
 * <h2>Handlers are public</h2>
 *
 * <p>Spring's method-security proxy does not advise a package-private method. A gated handler that is
 * package-private is a <b>silent no-op</b>: the annotation is present, the rule reads as enforced, and the
 * door is open. In the reference implementation every handler was package-private, which meant the entire
 * authorisation layer could have been decorative.
 */
@RestController
@RequestMapping("/admin/v1")
public class RoleAdminController {

    private final RoleAdministrationService roleAdministration;

    public RoleAdminController(RoleAdministrationService roleAdministration) {
        this.roleAdministration = roleAdministration;
    }

    @GetMapping("/roles")
    @PreAuthorize("@perm.has('" + Permissions.ROLE_READ + "')")
    public ApiResponse<List<RoleView>> roles() {
        return ApiResponse.ok(roleAdministration.roles());
    }

    @GetMapping("/permissions")
    @PreAuthorize("@perm.has('" + Permissions.PERMISSION_READ + "')")
    public ApiResponse<List<PermissionView>> permissions() {
        return ApiResponse.ok(roleAdministration.permissions());
    }

    /**
     * Give a staff member a role.
     *
     * <p>The recipient is in the path and the role in the body — one party per place, so the two cannot
     * disagree.
     */
    @PostMapping("/staff/uid/{uid}/roles")
    @PreAuthorize("@perm.has('" + Permissions.ROLE_GRANT + "')")
    public ApiResponse<Void> grant(@PathVariable UUID uid, @Valid @RequestBody GrantRoleRequest request) {
        roleAdministration.grant(uid, request.roleCode());
        return ApiResponse.done("Role granted.");
    }

    /**
     * Take a role away.
     *
     * <p>A separate permission from granting: widening access and narrowing it are different powers, and
     * there are people who should be able to do one and not the other during an incident.
     */
    @DeleteMapping("/staff/uid/{uid}/roles/{roleCode}")
    @PreAuthorize("@perm.has('" + Permissions.ROLE_REVOKE + "')")
    public ApiResponse<Void> revoke(@PathVariable UUID uid, @PathVariable String roleCode) {
        roleAdministration.revoke(uid, roleCode);
        return ApiResponse.done("Role revoked.");
    }
}
