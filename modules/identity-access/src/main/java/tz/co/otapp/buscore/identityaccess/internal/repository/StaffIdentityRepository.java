package tz.co.otapp.buscore.identityaccess.internal.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import tz.co.otapp.buscore.identityaccess.StaffTenancy;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffIdentity;

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
     * Every staff account. For platform staff, who belong to no company and administer all of them.
     *
     * <p>Ordered in the query rather than by the caller, so the two methods below cannot drift into
     * returning the same rows in different orders depending on who asked.
     */
    List<StaffIdentity> findAllByOrderByUsername();

    /**
     * The staff accounts of one company — the whole of an operator administrator's reach.
     *
     * <p><b>Filtered in the query, not afterwards.</b> Fetching everything and discarding what the caller
     * may not see gives the right answer today and the wrong one the moment the result is paged: page one
     * of a hundred rows, filtered down to three, looks to the caller like a total of three.
     *
     * <p>Two derived queries rather than one with a nullable bind: {@code where (:companyUid is null or ...)}
     * reads as elegant and puts an untyped null on the wire, which Postgres may refuse outright. Two methods
     * and a branch at the call site are duller and always work.
     */
    List<StaffIdentity> findAllByCompanyUidOrderByUsername(UUID companyUid);

    /**
     * Whether a sign-in name is taken, ignoring case.
     *
     * <p>Checked before an insert only to produce a usable message. It is <b>not</b> the guarantee — two
     * concurrent creates both see false. The functional unique indexes are what actually admit one.
     */
    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    /**
     * Whether a tenancy is already occupied.
     *
     * <p>Used only by the bootstrap to avoid an obviously-doomed insert. It is <b>not</b> the guarantee —
     * two concurrent bootstraps both see false here. The partial unique index on {@code tenancy = 'ROOT'}
     * is what actually admits exactly one.
     */
    boolean existsByTenancy(StaffTenancy tenancy);
}
