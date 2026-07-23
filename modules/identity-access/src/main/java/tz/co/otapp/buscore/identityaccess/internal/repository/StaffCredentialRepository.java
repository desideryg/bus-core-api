package tz.co.otapp.buscore.identityaccess.internal.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffCredential;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffIdentity;

/**
 * Password material for staff identities.
 *
 * <p>Returning {@link Optional} rather than a list is legitimate because the schema makes the foreign key
 * unique — one credential per identity. Without that constraint this signature would be a lie that happens
 * to hold.
 */
public interface StaffCredentialRepository extends JpaRepository<StaffCredential, Long> {

    Optional<StaffCredential> findByStaffIdentity(StaffIdentity staffIdentity);
}
