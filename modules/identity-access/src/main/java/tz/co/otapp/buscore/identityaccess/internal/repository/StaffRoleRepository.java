package tz.co.otapp.buscore.identityaccess.internal.repository;

import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tz.co.otapp.buscore.identityaccess.internal.domain.entity.Role;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffIdentity;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffRole;

/**
 * Role grants, and the one query that turns them into an answer.
 */
public interface StaffRoleRepository extends JpaRepository<StaffRole, Long> {

    /**
     * Every permission code the holder has, through every role they hold.
     *
     * <p>The one runtime read of a role in the whole system. Sign-in flattens roles into permission codes
     * and the role has done its job — which is why a route names a permission and never a role, and why a
     * role can be recomposed without touching one.
     *
     * <p><b>Archived roles are excluded here, and that is load-bearing.</b> Archiving otherwise blocks
     * only future grants while existing holders keep the access indefinitely — a role withdrawn from the
     * catalog that still confers everything it ever did, forever, with nothing indicating it. Filtering at
     * resolution is what makes archiving actually withdraw.
     *
     * <p>Returns a {@link Set} because two roles legitimately share a permission, and the caller wants the
     * distinct set rather than a list with duplicates it has to fold itself.
     */
    @Query("""
            select distinct p.code
            from StaffRole sr
            join RolePermission rp on rp.role = sr.role
            join Permission p on p = rp.permission
            where sr.staffIdentity = :staff
              and sr.role.archivedAt is null
            """)
    Set<String> findPermissionCodes(@Param("staff") StaffIdentity staff);

    List<StaffRole> findByStaffIdentity(StaffIdentity staffIdentity);

    boolean existsByStaffIdentityAndRole(StaffIdentity staffIdentity, Role role);

    void deleteByStaffIdentityAndRole(StaffIdentity staffIdentity, Role role);
}
