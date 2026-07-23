package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Set a password using a one-time token.
 *
 * <p>The token is the whole of the authorisation, which is why it is 256 bits of randomness rather than a
 * short code, and why presenting it does not name an account: the account is whichever one the token
 * belongs to. A request that named the account as well would let a caller aim a valid token at a different
 * account, and the server would have to decide which to believe.
 *
 * @param token       the value issued to the holder, exactly once
 * @param newPassword the password being set
 */
public record RedeemPasswordResetRequest(

        @NotBlank(message = "The reset token is missing.")
        String token,

        @NotBlank(message = "Enter a new password.")
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
                message = PasswordPolicy.MESSAGE)
        String newPassword) {
}
