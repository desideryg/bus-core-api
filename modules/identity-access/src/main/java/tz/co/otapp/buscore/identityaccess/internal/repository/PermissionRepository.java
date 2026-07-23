package tz.co.otapp.buscore.identityaccess.internal.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tz.co.otapp.buscore.identityaccess.internal.domain.entity.Permission;

/**
 * The permission rows.
 *
 * <p>Read far more often by the seed and by the catalog test than by anything at runtime: a sign-in
 * resolves permission CODES through the role join, not these entities.
 */
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findByCode(String code);

    List<Permission> findAllByOrderByCode();
}
