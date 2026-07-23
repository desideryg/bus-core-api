package tz.co.otapp.buscore.identityaccess.internal.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Getter;
import tz.co.otapp.buscore.shared.abstraction.BaseEntity;

/**
 * One permission granted to one role.
 *
 * <p>A full entity rather than a {@code @ManyToMany} join table, and that is a decision rather than
 * ceremony: <b>a grant is a row in its own right.</b> It was made at a moment, and a later slice will
 * record who made it. An anonymous pair in a link table has nowhere to put either, and retrofitting the
 * columns means changing how the association is mapped everywhere it is used.
 *
 * <p>The database enforces one row per {@code (role, permission)}. That is what makes granting idempotent
 * — the same grant twice inserts one row or fails, never two — and therefore what lets a revoke actually
 * revoke rather than remove half a grant.
 */
@Getter
@Entity
@Table(name = "role_permissions")
public class RolePermission extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "permission_id", nullable = false)
    private Permission permission;

    protected RolePermission() {
    }

    private RolePermission(Role role, Permission permission) {
        this.role = role;
        this.permission = permission;
    }

    public static RolePermission of(Role role, Permission permission) {
        return new RolePermission(role, permission);
    }
}
