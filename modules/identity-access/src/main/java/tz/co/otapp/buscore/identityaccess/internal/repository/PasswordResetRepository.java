package tz.co.otapp.buscore.identityaccess.internal.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tz.co.otapp.buscore.identityaccess.internal.domain.entity.PasswordReset;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffIdentity;

/**
 * One-time password resets.
 */
public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {

    /**
     * Find a reset by the fingerprint of the presented token.
     *
     * <p>The only way a redemption can locate a row: the caller presents a token and has not proved which
     * account they are, so nothing else is available to look up by.
     *
     * <p>Returning {@link Optional} is safe because the schema makes the hash unique.
     */
    Optional<PasswordReset> findByTokenHash(String tokenHash);

    /**
     * The account's outstanding resets — at most one, by the partial unique index.
     *
     * <p>Returns a list rather than an Optional deliberately. The index guarantees one, but this query is
     * what <em>maintains</em> the guarantee by superseding whatever it finds, and a signature that asserted
     * the invariant it is enforcing would blow up on the row it exists to clean away.
     */
    List<PasswordReset> findAllByStaffIdentityAndConsumedAtIsNull(StaffIdentity staffIdentity);
}
