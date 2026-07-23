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
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.CreateStaffRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.StaffView;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.SuspendStaffRequest;
import tz.co.otapp.buscore.identityaccess.internal.service.StaffAdministrationService;

/**
 * The staff administration surface.
 *
 * <p>Handlers are <b>public</b> and every {@code @PreAuthorize} concatenates a {@link Permissions} constant
 * rather than spelling a string. Both rules are load-bearing and both fail silently when broken: Spring's
 * method-security proxy does not advise a package-private method, and SpEL is not compiled, so a typo
 * inside the quotes is a permission nobody holds rather than an error. See {@link RoleAdminController} for
 * the full reasoning.
 *
 * <h2>What the routes are shaped like</h2>
 *
 * <p>A withdrawal is modelled as a <b>suspension resource</b> — created with {@code POST}, removed with
 * {@code DELETE} — rather than as a status field a caller may set to anything. That is what lets the two
 * directions carry separate permissions, which is the whole point of splitting them: during an incident the
 * people who should be able to cut an account off are not always the people who should be able to turn it
 * back on.
 *
 * <p><b>The subject is always in the path and never in the body.</b> A body naming both parties makes it
 * possible for the two to disagree.
 */
@RestController
@RequestMapping("/admin/v1/staff")
public class StaffAdminController {

    private final StaffAdministrationService staffAdministration;

    public StaffAdminController(StaffAdministrationService staffAdministration) {
        this.staffAdministration = staffAdministration;
    }

    /**
     * Provision an account.
     *
     * <p>What the caller may create is not expressible as a permission — it depends on their own tenancy
     * and company — so {@code STAFF.CREATE} opens the route and the service decides the rest.
     */
    @PostMapping
    @PreAuthorize("@perm.has('" + Permissions.STAFF_CREATE + "')")
    public ApiResponse<StaffView> create(@Valid @RequestBody CreateStaffRequest request) {
        return ApiResponse.created(staffAdministration.create(request), "Staff account created.");
    }

    /** The accounts the caller administers. Filtered by company in the query, never afterwards. */
    @GetMapping
    @PreAuthorize("@perm.has('" + Permissions.STAFF_READ + "')")
    public ApiResponse<List<StaffView>> list() {
        return ApiResponse.ok(staffAdministration.list());
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('" + Permissions.STAFF_READ + "')")
    public ApiResponse<StaffView> get(@PathVariable UUID uid) {
        return ApiResponse.ok(staffAdministration.get(uid));
    }

    /**
     * The operators an account serves.
     *
     * <p>Gated by {@code STAFF.READ} rather than a membership-specific code: it discloses nothing beyond
     * what reading the account already does, and a permission per projection of one resource is a catalog
     * nobody can reason about.
     */
    @GetMapping("/uid/{uid}/operators")
    @PreAuthorize("@perm.has('" + Permissions.STAFF_READ + "')")
    public ApiResponse<List<UUID>> operators(@PathVariable UUID uid) {
        return ApiResponse.ok(staffAdministration.operatorsOf(uid));
    }

    /** Withdraw access — suspended for now, blocked for good; the caller says which. */
    @PostMapping("/uid/{uid}/suspension")
    @PreAuthorize("@perm.has('" + Permissions.STAFF_SUSPEND + "')")
    public ApiResponse<Void> suspend(@PathVariable UUID uid, @Valid @RequestBody SuspendStaffRequest request) {
        staffAdministration.suspend(uid, request);
        return ApiResponse.done("Access withdrawn.");
    }

    /** Return access. A separate permission from withdrawing it. */
    @DeleteMapping("/uid/{uid}/suspension")
    @PreAuthorize("@perm.has('" + Permissions.STAFF_RESTORE + "')")
    public ApiResponse<Void> restore(@PathVariable UUID uid) {
        staffAdministration.restore(uid);
        return ApiResponse.done("Access restored.");
    }

    /**
     * Attach an operator.
     *
     * <p>Both parties are path segments, so there is no body at all and therefore nothing for a caller to
     * disagree with the path about.
     */
    @PostMapping("/uid/{uid}/operators/{operatorUid}")
    @PreAuthorize("@perm.has('" + Permissions.STAFF_OPERATOR_LINK + "')")
    public ApiResponse<Void> link(@PathVariable UUID uid, @PathVariable UUID operatorUid) {
        staffAdministration.linkOperator(uid, operatorUid);
        return ApiResponse.done("Operator linked.");
    }

    /** Detach one. */
    @DeleteMapping("/uid/{uid}/operators/{operatorUid}")
    @PreAuthorize("@perm.has('" + Permissions.STAFF_OPERATOR_UNLINK + "')")
    public ApiResponse<Void> unlink(@PathVariable UUID uid, @PathVariable UUID operatorUid) {
        staffAdministration.unlinkOperator(uid, operatorUid);
        return ApiResponse.done("Operator unlinked.");
    }
}
