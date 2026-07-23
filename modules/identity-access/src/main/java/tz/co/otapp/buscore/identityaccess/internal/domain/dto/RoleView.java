package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

import java.util.UUID;

import tz.co.otapp.buscore.identityaccess.internal.domain.entity.Role;

/**
 * A role as an administrator sees it when choosing one to grant.
 *
 * @param uid           the handle a grant names
 * @param code          the stable identifier
 * @param name          human label
 * @param description   what holding it lets somebody do
 * @param holderTenancy which class of staff may hold it — a client uses this to avoid offering a role
 *                      that would be refused
 * @param archived      whether it can still be granted. Archived roles are shown rather than hidden, so
 *                      somebody looking for a role that has gone finds out that it went
 */
public record RoleView(UUID uid, String code, String name, String description,
                       String holderTenancy, boolean archived) {

    public static RoleView of(Role role) {
        return new RoleView(role.getUid(), role.getCode(), role.getName(), role.getDescription(),
                role.getHolderTenancy().getValue(), !role.isGrantable());
    }
}
