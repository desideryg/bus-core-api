package tz.co.otapp.buscore.identityaccess.internal.domain.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Getter;
import tz.co.otapp.buscore.shared.abstraction.BaseEntity;

/**
 * One operator a staff member serves.
 *
 * <p>Many per person, deliberately: a shared-services employee covering two depots is one account with two
 * memberships, not two accounts with two passwords and two audit trails.
 *
 * <p>{@code operatorUid} is a <b>bare handle</b> into the tenancy module — no foreign key, no association,
 * never joined. A constraint would weld this module to another's tables and invert the dependency arrow.
 * The cost is that nothing detects a stale handle, so it must fail closed wherever it is resolved.
 */
@Getter
@Entity
@Table(name = "staff_operators")
public class StaffOperator extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_identity_id", nullable = false)
    private StaffIdentity staffIdentity;

    @Column(name = "operator_uid", nullable = false)
    private UUID operatorUid;

    /**
     * The company the operator sits under.
     *
     * <p>Duplicated from the staff member's own record on purpose: it is what the composite foreign key
     * compares against, and identity-access cannot join to the tenancy module to look it up. Written once
     * and never updated — an operator does not change company.
     */
    @Column(name = "company_uid", nullable = false)
    private UUID companyUid;

    protected StaffOperator() {
    }

    private StaffOperator(StaffIdentity staffIdentity, UUID operatorUid, UUID companyUid) {
        this.staffIdentity = staffIdentity;
        this.operatorUid = operatorUid;
        this.companyUid = companyUid;
    }

    /**
     * A membership, taking its company from the staff member.
     *
     * <p>The company is not a parameter: it comes from the person being linked, so a caller cannot supply
     * one that disagrees. Even if they could, the composite foreign key would refuse the row.
     */
    public static StaffOperator of(StaffIdentity staffIdentity, UUID operatorUid) {
        return new StaffOperator(staffIdentity, operatorUid, staffIdentity.getCompanyUid());
    }
}
