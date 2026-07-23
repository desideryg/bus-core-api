package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An edit to an existing staff account.
 *
 * <p><b>Display name only, and the omissions are the design.</b> The <em>username</em> is the handle a
 * person types to sign in and the <em>email</em> is the address a password recovery is sent to; changing
 * either silently breaks a sign-in or redirects a recovery, so neither is editable through this surface.
 * <em>Tenancy</em> and <em>company</em> decide whose data the account reaches — an access change, not a
 * detail edit — and <em>status</em> is withdrawn and restored through its own permissioned routes. What is
 * left, and all this endpoint is for, is correcting how the person is shown.
 *
 * <p>The single field is still required rather than optional: this endpoint sets the display name, and a
 * request that named nothing to change would be a no-op dressed as an edit. When there is more than one
 * editable field the shape becomes a partial update; there is one, so it does not.
 *
 * @param displayName how the person is shown to others — the same rule the create surface applies
 */
public record UpdateStaffRequest(

        @NotBlank(message = "Give the account a display name.")
        @Size(max = 128, message = "A display name may be at most 128 characters.")
        String displayName) {
}
