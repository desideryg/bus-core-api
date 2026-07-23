package tz.co.otapp.buscore.identityaccess.internal.service;

import java.time.Instant;
import java.util.UUID;

import tz.co.otapp.buscore.identityaccess.PrincipalType;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.SessionRevocationReason;

/**
 * The lifecycle of a session and its rotating refresh token — opening one at sign-in, renewing it, and
 * ending it.
 *
 * <p><b>It mints no access tokens and resolves no authority.</b> That stays with the staff and agent
 * authentication services, which know how to turn a principal uid into a token; this service knows only how
 * to prove a refresh token still names a live session and to rotate it. The split is deliberate: a session
 * is principal-agnostic and identical for both surfaces, while resolving what a principal may do is entirely
 * different between them — an agent has no permissions to resolve at all.
 *
 * <h2>Rotation, reuse detection, revocation</h2>
 *
 * <p>These are the three the module said a refresh token could not ship without, and they live here.
 * {@link #rotate} spends the presented token and issues a successor; a presented token that was already
 * spent is reuse, and it revokes the whole session rather than renewing it. {@link #logout} and {@link
 * #revokeAllFor} are the deliberate ends — a holder signing out, and everything else that must not leave a
 * session alive: a suspension, a password change, a recovery.
 */
public interface AuthSessionService {

    /**
     * Open a session and mint its first refresh token.
     *
     * <p>Called at the end of a successful sign-in, once the principal is known. The caller already holds the
     * uid, so this returns only what it does not have: the token to hand back and when the session lapses.
     */
    IssuedRefresh open(UUID principalUid, PrincipalType type);

    /**
     * Renew a session: prove the presented token names a live one, spend it, and issue a successor.
     *
     * <p>Does not re-resolve the principal or mint an access token — it returns the uid so the caller can do
     * both against the right identity table. A refused renewal throws {@code AUTH.REFRESH_TOKEN_INVALID} for
     * every cause, including a reuse that has just revoked the session.
     *
     * @throws tz.co.otapp.buscore.apicontracts.error.ApiException {@code AUTH.REFRESH_TOKEN_INVALID} when the
     *         token is unknown, for another surface, revoked, expired, or a replay of a spent token
     */
    RotatedRefresh rotate(String rawRefreshToken, PrincipalType expectedType);

    /**
     * End the session a refresh token names, if it still names a live one.
     *
     * <p>Idempotent and never throws: an unknown, wrong-surface, or already-ended token is the state logout
     * is trying to reach, so it is reported as success. That also keeps this public endpoint from telling a
     * caller whether a token was real.
     */
    void logout(String rawRefreshToken, PrincipalType expectedType);

    /**
     * End every live session a principal holds.
     *
     * <p>The revocation lever the rest of the module reaches for when a credential or a status changes under
     * a session that must not survive it. A no-op when the principal holds none.
     */
    void revokeAllFor(UUID principalUid, PrincipalType type, SessionRevocationReason reason);

    /** A freshly opened session's refresh token and the moment the session lapses. */
    record IssuedRefresh(String refreshToken, Instant expiresAt) {
    }

    /** A renewed session: whose it is, the successor token, and the unchanged absolute expiry. */
    record RotatedRefresh(UUID principalUid, String refreshToken, Instant expiresAt) {
    }
}
