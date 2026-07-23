package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A refresh token presented to renew a session, or to end one.
 *
 * <p>The same body serves {@code /refresh} and {@code /logout}: both carry only the token, and both are the
 * one field a caller has to prove possession of. A separate record per endpoint would be two identical
 * shapes maintained apart — the fields are what distinguish the credential DTOs from each other, and here
 * they do not differ.
 *
 * @param refreshToken the token minted at sign-in or by the previous refresh. Length-capped so an enormous
 *                     value cannot be used to make the database fingerprint and look up megabytes before the
 *                     request is even recognised — the same guard {@code LoginRequest} puts on its fields
 */
public record RefreshRequest(

        @NotBlank(message = "A refresh token is required.")
        @Size(max = 512, message = "That refresh token is too long.")
        String refreshToken) {
}
