package tz.co.otapp.buscore.identityaccess.internal.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Getter;
import tz.co.otapp.buscore.shared.abstraction.BaseEntity;

/**
 * A single-use credential that renews a {@link AuthSession}.
 *
 * <p>Presented only to the refresh endpoint, never as a bearer on ordinary calls, so it is exposed far less
 * than the access token it renews — which is the whole reason the long-lived credential is this and not a
 * longer access token.
 *
 * <h2>It rotates, and the spent ones are kept</h2>
 *
 * <p>Every refresh spends the presented token and issues a successor in the same session. The spent row is
 * <b>marked {@link #consumedAt}, not deleted</b>, and that surviving mark is load-bearing: a token presented
 * with {@code consumedAt} already set is a replay of a credential that was rotated away, which is the
 * signature of a stolen one. The service answers that by revoking the whole session — see {@code
 * AuthSessionService}. A design that deleted spent tokens could not tell that replay from an unknown token.
 *
 * <h2>The row holds a hash, never the token</h2>
 *
 * <p>The {@link PasswordReset} argument, unchanged: a refresh token is a bearer credential, so storing it in
 * the clear would make read access to this table equivalent to a live session on every account that holds
 * one. The token exists exactly once, in the response that minted it, and is only ever recognised by
 * comparing the fingerprint of a presented value against {@link #tokenHash}.
 */
@Getter
@Entity
@Table(name = "auth_refresh_tokens")
public class AuthRefreshToken extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private AuthSession session;

    /** Hex of SHA-256. Deterministic on purpose — a refresh can only look up, never name the session. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** Null until spent. Kept rather than cleared, because a replayed spent token is how theft shows. */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected AuthRefreshToken() {
    }

    private AuthRefreshToken(AuthSession session, String tokenHash) {
        this.session = session;
        this.tokenHash = tokenHash;
    }

    public static AuthRefreshToken issue(AuthSession session, String tokenHash) {
        return new AuthRefreshToken(session, tokenHash);
    }

    /** Whether this token has not yet been spent. Its session's liveness is a separate question. */
    public boolean isLive() {
        return consumedAt == null;
    }

    /**
     * Spend it.
     *
     * <p>Idempotence is deliberately absent: a second spend must be visible, because that is precisely the
     * replay {@link #consumedAt} exists to catch. The caller checks {@link #isLive()} on the way in and
     * treats an already-spent token as reuse rather than re-consuming it.
     */
    public void consume(Instant now) {
        this.consumedAt = now;
    }
}
