package tz.co.otapp.buscore.identityaccess.internal.service;

import java.util.List;
import java.util.UUID;

import tz.co.otapp.buscore.identityaccess.internal.domain.dto.PermissionView;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.RoleView;

/**
 * Reading the catalog, and moving roles on and off staff accounts.
 *
 * <p>Separate from sign-in on purpose: a caller that only needs to know who is acting must not compile
 * against the ability to change what anyone may do.
 */
public interface RoleAdministrationService {

    List<RoleView> roles();

    List<PermissionView> permissions();

    /**
     * Give a staff member a role.
     *
     * <p>Idempotent: granting a role the person already holds succeeds and changes nothing. A grant is a
     * statement about the desired end state, and refusing the second one would make a retried request an
     * error for no reason a caller could act on.
     *
     * @throws tz.co.otapp.buscore.apicontracts.error.ApiException
     *         {@code AUTH.ROLE_NOT_GRANTABLE} when the role is archived, or when the staff member's
     *         tenancy is not the one the role is meant for
     */
    void grant(UUID staffUid, String roleCode);

    /**
     * Take a role away.
     *
     * <p>Also idempotent: revoking a role that is not held succeeds. During an incident the useful question
     * is "do they have it now", and an error for "they already did not" is noise at the worst moment.
     *
     * <p><b>It does not take effect immediately.</b> Permissions are carried in the token, so the person
     * keeps the access until their token expires. Where that matters, the lever is session revocation.
     */
    void revoke(UUID staffUid, String roleCode);
}
