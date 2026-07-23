package tz.co.otapp.buscore.identityaccess.internal.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffIdentity;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffOperator;

/**
 * Operator memberships.
 */
public interface StaffOperatorRepository extends JpaRepository<StaffOperator, Long> {

    /**
     * The operators this staff member serves.
     *
     * <p>Read once at sign-in and carried in the token. Returns the uids rather than the entities: the
     * caller wants handles, and loading rows to throw away everything but one column is work nobody asked
     * for.
     */
    @Query("select so.operatorUid from StaffOperator so where so.staffIdentity = :staff")
    List<UUID> findOperatorUids(@Param("staff") StaffIdentity staff);

    boolean existsByStaffIdentityAndOperatorUid(StaffIdentity staffIdentity, UUID operatorUid);

    void deleteByStaffIdentityAndOperatorUid(StaffIdentity staffIdentity, UUID operatorUid);
}
