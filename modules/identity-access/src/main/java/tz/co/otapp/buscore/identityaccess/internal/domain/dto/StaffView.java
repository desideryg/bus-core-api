package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

import java.util.UUID;

import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffIdentity;

/**
 * A staff account as a caller may see it.
 *
 * <p>A separate type from the entity, even though the fields presently overlap almost exactly. They
 * diverge the first time a column is added that a caller must not see — and a response that <em>is</em> an
 * entity has no way to withhold it. Returning the entity would also expose whatever a future association
 * drags along, and serialise a lazy proxy at whatever moment the writer happens to touch it.
 *
 * <p>Note the absence of the numeric id: {@link #uid} is the only handle that crosses a boundary.
 *
 * @param uid         the public handle
 * @param username    the sign-in name, in the casing the person chose
 * @param email       their address
 * @param displayName how they are shown to other people
 * @param tenancy     which organisation they belong to, as the stable code
 * @param status      whether the account is usable, as the stable code
 * @param companyUid  the company an operator account belongs to; null for everyone else. Included because
 *                    an administrator listing accounts needs to see which company each one reaches, and it
 *                    is the anchor of the cross-company guard
 */
public record StaffView(
        UUID uid,
        String username,
        String email,
        String displayName,
        String tenancy,
        String status,
        UUID companyUid) {

    /**
     * Project an entity onto the view.
     *
     * <p>Enums are rendered as their stable code rather than their display label — a client branches on
     * the code, and a label is copy that may be reworded at any time. The label and description are
     * available to a client through the enum-describing contract when it needs to show them.
     */
    public static StaffView of(StaffIdentity identity) {
        return new StaffView(
                identity.getUid(),
                identity.getUsername(),
                identity.getEmail(),
                identity.getDisplayName(),
                identity.getTenancy().getValue(),
                identity.getStatus().getValue(),
                identity.getCompanyUid());
    }
}
