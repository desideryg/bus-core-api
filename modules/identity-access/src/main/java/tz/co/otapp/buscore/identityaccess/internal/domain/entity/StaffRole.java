package tz.co.otapp.buscore.identityaccess.internal.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Getter;
import tz.co.otapp.buscore.shared.abstraction.BaseEntity;

/**
 * One role held by one staff member.
 *
 * <p>A full entity for the same reason as {@link RolePermission}: a grant is a fact with a time, not an
 * anonymous pair. Uniqueness on {@code (staff, role)} is what makes granting idempotent and revoking
 * complete.
 *
 * <p><b>There is no agent side to this table, and there never will be.</b> Agents are authorised by selling
 * grants in their own module, never by roles — a column here for them would make it possible to grant an
 * agent a permission, which is the confusion the whole staff/agent split exists to prevent.
 */
@Getter
@Entity
@Table(name = "staff_roles")
public class StaffRole extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_identity_id", nullable = false)
    private StaffIdentity staffIdentity;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    protected StaffRole() {
    }

    private StaffRole(StaffIdentity staffIdentity, Role role) {
        this.staffIdentity = staffIdentity;
        this.role = role;
    }

    public static StaffRole of(StaffIdentity staffIdentity, Role role) {
        return new StaffRole(staffIdentity, role);
    }
}
