package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Change a password by presenting the current one.
 *
 * <p><b>Unauthenticated, and that is the design.</b> An account required to rotate its password is refused
 * a token at sign-in — issuing one "so the change endpoint can be called" would make the account fully
 * usable while nominally requiring a rotation — so the holder has no token to present. Proving the current
 * password is what authorises the change, and it works identically whether the rotation was forced or the
 * holder simply chose to change it. One path rather than two that drift.
 *
 * <p>Because it verifies a password, this route <b>counts failures and honours the lockout</b> exactly as
 * sign-in does. Without that it would be a password-guessing oracle with the lockout bypassed, sitting
 * beside the endpoint the lockout protects.
 *
 * @param identifier      username or email — the same value sign-in takes
 * @param currentPassword what authorises the change
 * @param newPassword     the replacement, which must differ from the current one
 */
public record ChangePasswordRequest(

        @NotBlank(message = "Enter your username or email address.")
        String identifier,

        @NotBlank(message = "Enter your current password.")
        String currentPassword,

        @NotBlank(message = "Enter a new password.")
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
                message = PasswordPolicy.MESSAGE)
        String newPassword) {
}
