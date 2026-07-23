package tz.co.otapp.buscore.identityaccess.internal.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffIdentity;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.StaffTenancy;

/**
 * Staff login identities.
 *
 * <p><b>Every lookup by username or email is case-insensitive, and must stay that way.</b> The schema
 * enforces uniqueness with functional indexes on {@code lower(username)} and {@code lower(email)}, so a
 * case-sensitive query does not fail — it silently disagrees with the constraint, finding nothing for
 * {@code Alice} when the row was stored as {@code alice}. The person is then told their credentials are
 * invalid, and no error anywhere explains why.
 */
public interface StaffIdentityRepository extends JpaRepository<StaffIdentity, Long> {

    /**
     * Resolve a sign-in identifier, which may be either a username or an email address.
     *
     * <p>One query rather than two sequential lookups: two would make an email-holder's sign-in measurably
     * slower than a username-holder's, and a timing difference on the resolution step is exactly the kind
     * of signal the indistinguishable-refusal rule exists to remove.
     */
    Optional<StaffIdentity> findByUsernameIgnoreCaseOrEmailIgnoreCase(String username, String email);

    /** The public handle is what other modules and URLs carry; the numeric id never leaves this module. */
    Optional<StaffIdentity> findByUid(UUID uid);

    /**
     * Whether a tenancy is already occupied.
     *
     * <p>Used only by the bootstrap to avoid an obviously-doomed insert. It is <b>not</b> the guarantee —
     * two concurrent bootstraps both see false here. The partial unique index on {@code tenancy = 'ROOT'}
     * is what actually admits exactly one.
     */
    boolean existsByTenancy(StaffTenancy tenancy);
}
