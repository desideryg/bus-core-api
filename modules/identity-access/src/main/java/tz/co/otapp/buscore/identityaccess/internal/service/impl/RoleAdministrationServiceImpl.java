package tz.co.otapp.buscore.identityaccess.internal.service.impl;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import tz.co.otapp.buscore.apicontracts.error.ApiException;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.PermissionView;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.RoleView;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.Role;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffIdentity;
import tz.co.otapp.buscore.identityaccess.PrincipalType;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffRole;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.AuthEventType;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.IdentityErrors;
import tz.co.otapp.buscore.identityaccess.internal.repository.PermissionRepository;
import tz.co.otapp.buscore.identityaccess.internal.repository.RoleRepository;
import tz.co.otapp.buscore.identityaccess.internal.repository.StaffIdentityRepository;
import tz.co.otapp.buscore.identityaccess.internal.repository.StaffRoleRepository;
import tz.co.otapp.buscore.identityaccess.internal.service.AuthAuditRecorder;
import tz.co.otapp.buscore.identityaccess.internal.service.RoleAdministrationService;

/**
 * Role administration.
 *
 * <p>Both mutations are <b>idempotent</b>, which the schema makes possible: uniqueness on
 * {@code (staff, role)} means a grant is one row or none, so "already granted" is a no-op rather than a
 * duplicate, and a revoke removes the grant rather than one of two.
 */
@Service
@Transactional
@Slf4j
public class RoleAdministrationServiceImpl implements RoleAdministrationService {

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final StaffIdentityRepository identities;
    private final StaffRoleRepository staffRoles;
    private final AuthAuditRecorder auditRecorder;

    public RoleAdministrationServiceImpl(RoleRepository roles, PermissionRepository permissions,
            StaffIdentityRepository identities, StaffRoleRepository staffRoles,
            AuthAuditRecorder auditRecorder) {
        this.roles = roles;
        this.permissions = permissions;
        this.identities = identities;
        this.staffRoles = staffRoles;
        this.auditRecorder = auditRecorder;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleView> roles() {
        return roles.findAll().stream().map(RoleView::of).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PermissionView> permissions() {
        return permissions.findAllByOrderByCode().stream().map(PermissionView::of).toList();
    }

    @Override
    public void grant(UUID staffUid, String roleCode) {
        StaffIdentity staff = requireStaff(staffUid);
        Role role = requireRole(roleCode);

        if (!role.isGrantable()) {
            throw new ApiException(IdentityErrors.ROLE_NOT_GRANTABLE,
                    "The role '" + roleCode + "' has been archived and can no longer be granted.");
        }

        // A role is declared for one class of staff. Enforced here rather than at each call site, so a new
        // grant surface cannot forget it — the reference implementation had exactly this hole, where an
        // operator-held role carried permissions over staff administration.
        if (role.getHolderTenancy() != staff.getTenancy()) {
            throw new ApiException(IdentityErrors.ROLE_NOT_GRANTABLE,
                    "The role '" + roleCode + "' is for " + role.getHolderTenancy().getName()
                            + " accounts and cannot be granted to this one.");
        }

        if (staffRoles.existsByStaffIdentityAndRole(staff, role)) {
            // Idempotent. A retried request must not be an error a caller can do nothing about.
            log.debug("{} already holds {}", staffUid, roleCode);
            return;
        }

        staffRoles.save(StaffRole.of(staff, role));
        auditRecorder.record(AuthEventType.ROLE_GRANTED, PrincipalType.STAFF, staffUid, roleCode);
        log.info("granted {} to {}", roleCode, staffUid);
    }

    @Override
    public void revoke(UUID staffUid, String roleCode) {
        StaffIdentity staff = requireStaff(staffUid);
        Role role = requireRole(roleCode);

        // deleteBy… removes nothing when nothing matches, which is the idempotent behaviour wanted. During
        // an incident the question is "do they have it now", and an error for "they already did not" is
        // noise at the worst possible moment.
        staffRoles.deleteByStaffIdentityAndRole(staff, role);
        auditRecorder.record(AuthEventType.ROLE_REVOKED, PrincipalType.STAFF, staffUid, roleCode);
        log.info("revoked {} from {}", roleCode, staffUid);
    }

    private StaffIdentity requireStaff(UUID staffUid) {
        return identities.findByUid(staffUid)
                .orElseThrow(() -> new ApiException(IdentityErrors.STAFF_NOT_FOUND));
    }

    private Role requireRole(String roleCode) {
        return roles.findByCode(roleCode)
                .orElseThrow(() -> new ApiException(IdentityErrors.ROLE_NOT_FOUND,
                        "No role with code '" + roleCode + "'."));
    }
}
