package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

import tz.co.otapp.buscore.identityaccess.internal.domain.entity.Permission;

/**
 * A permission as it appears in the catalog.
 *
 * <p>No uid: a permission is addressed by its code everywhere — in a role composition, in a route
 * annotation, in a token. Publishing a second identifier would invite half the system to use it.
 *
 * @param code        the {@code DOMAIN.ACTION} string
 * @param description what holding it allows, in plain words. Not decoration — whoever composes a role is
 *                    choosing from a list of codes, and {@code STAFF.READ} does not explain its own limits
 */
public record PermissionView(String code, String description) {

    public static PermissionView of(Permission permission) {
        return new PermissionView(permission.getCode(), permission.getDescription());
    }
}
