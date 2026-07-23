package tz.co.otapp.buscore.identityaccess.internal.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import tz.co.otapp.buscore.identityaccess.StaffTenancy;
import tz.co.otapp.buscore.shared.abstraction.BaseEntity;
import tz.co.otapp.buscore.shared.time.Times;

/**
 * A named bundle of permissions.
 *
 * <p>Roles exist to make granting manageable — nobody assigns forty permissions by hand — but
 * <b>the runtime check is always on the permission, never on the role</b>. That indirection is what lets a
 * role be renamed or recomposed without touching a single route.
 *
 * <p>The one place a role is read at runtime is sign-in, which flattens the holder's roles into a set of
 * permission codes. After that the role has done its job.
 */
@Entity
@Table(name = "roles")
public class Role extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", nullable = false, length = 512)
    private String description;

    /**
     * Which class of staff may hold this role.
     *
     * <p>A role meant for operator staff must not be grantable to a partner. Storing the constraint on the
     * role is what lets the grant surface enforce it once, rather than every grant site remembering which
     * roles suit which tenancy.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "holder_tenancy", nullable = false, length = 32)
    private StaffTenancy holderTenancy;

    /**
     * When the role stopped being grantable. Null while it is live.
     *
     * <p>A timestamp rather than a boolean, so the record says <em>when</em> — which is the first question
     * asked when somebody notices a role has gone.
     *
     * <p><b>Archiving blocks future grants and nothing else.</b> Existing holders keep the permissions the
     * role gave them until it is revoked from each. That is a deliberate, and easily-missed, limitation:
     * archiving is not a way to withdraw access in a hurry.
     */
    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Role() {
    }

    public boolean isGrantable() {
        return archivedAt == null;
    }

    /** Idempotent: archiving an archived role keeps the original moment rather than moving it. */
    public void archive() {
        if (archivedAt == null) {
            archivedAt = Times.now();
        }
    }

    public void restore() {
        archivedAt = null;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public StaffTenancy getHolderTenancy() {
        return holderTenancy;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
