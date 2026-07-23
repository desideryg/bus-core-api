package tz.co.otapp.buscore.identityaccess.internal.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import tz.co.otapp.buscore.identityaccess.internal.domain.entity.Role;

/**
 * Roles, looked up by the code a grant names or the uid a URL carries.
 */
public interface RoleRepository extends JpaRepository<Role, Long> {

    /** The seed and the grant surface both address a role by its code rather than its uid. */
    Optional<Role> findByCode(String code);

    Optional<Role> findByUid(UUID uid);

    /** Live roles only — an archived role is not offered as something to grant. */
    List<Role> findByArchivedAtIsNullOrderByCode();
}
