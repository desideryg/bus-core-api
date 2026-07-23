package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

import java.time.Instant;

/**
 * A freshly issued reset token — the one and only time its value exists outside the holder's hands.
 *
 * <p>The database keeps a SHA-256 of it and nothing more, so this response cannot be reproduced. If it is
 * lost the remedy is to issue another, which supersedes this one.
 *
 * <h2>A stated limitation, not an oversight</h2>
 *
 * <p>The token is returned to the <b>administrator</b>, who must pass it to the holder out of band. That
 * means the administrator sees it, and could redeem it themselves — so this endpoint is the power to take
 * over an account, and it carries its own permission for that reason.
 *
 * <p>The right answer is to deliver it straight to the holder's address and return nothing here. That needs
 * the {@code notification} module, which is a later wave. When it lands, this record becomes an expiry and
 * no token, and nothing else about the flow changes.
 *
 * @param token     the value to give the holder. Never logged, never stored, never shown again
 * @param expiresAt when it stops working
 */
public record PasswordResetIssued(String token, Instant expiresAt) {
}
