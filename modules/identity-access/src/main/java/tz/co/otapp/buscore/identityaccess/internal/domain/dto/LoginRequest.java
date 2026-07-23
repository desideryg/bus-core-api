package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sign-in credentials.
 *
 * <p><b>The identifier is a username or an email address</b>, one field rather than two. Two fields would
 * force the caller to declare which they are using, and a caller who picks wrong gets refused for a reason
 * that is not their fault.
 *
 * <p>Validation here is only about shape, never about policy. A minimum password length would tell an
 * attacker the policy for free and would refuse a legitimate holder of a shorter legacy password before
 * their credentials were ever checked — a validation error and an authentication failure are different
 * answers, and the difference is information.
 *
 * @param identifier username or email. Length-capped so an enormous value cannot be used to make the
 *                   database do expensive work before authentication has happened.
 * @param password   the presented password, capped for the same reason — hashing is deliberately slow, and
 *                   an unbounded input is a cheap way to make the server do a lot of it.
 */
public record LoginRequest(

        @NotBlank(message = "Enter your username or email address.")
        @Size(max = 128, message = "That identifier is too long.")
        String identifier,

        @NotBlank(message = "Enter your password.")
        @Size(max = 200, message = "That password is too long.")
        String password) {
}
