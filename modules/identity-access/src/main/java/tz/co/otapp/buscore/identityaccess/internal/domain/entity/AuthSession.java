package tz.co.otapp.buscore.identityaccess.internal.domain.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import lombok.Getter;
import tz.co.otapp.buscore.identityaccess.PrincipalType;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.SessionRevocationReason;
import tz.co.otapp.buscore.shared.abstraction.BaseEntity;

/**
 * A sign-in that can be ended before it expires.
 *
 * <p>The one piece of server-side authentication state the module was built without. An access token is
 * trusted unread until it expires, so nothing about it can be revoked; a session is the thing revocation
 * acts on, and the access token merely outlives it by at most its own short lifetime.
 *
 * <h2>Principal-agnostic, and why there is no association</h2>
 *
 * <p>A session belongs to a staff login or an agent login, and {@link #principalUid} is a bare handle rather
 * than a {@code @ManyToOne} because no single column can reference two identity tables. This mirrors {@link
 * AuthAuditEvent}, which records the same pair for the same reason. A refresh re-resolves the principal from
 * this uid against the right table, so the session never has to know which one it named beyond {@link
 * #principalType} — which is also what keeps a session confined to the surface it was minted on.
 *
 * <h2>The expiry is absolute</h2>
 *
 * <p>{@link #expiresAt} is set once, at sign-in, and refreshing never moves it. A session therefore lives at
 * most one refresh lifetime from the moment it opened, however continuously it is used — the bound a refresh
 * token exists to impose, which a sliding expiry would quietly remove.
 */
@Getter
@Entity
@Table(name = "auth_sessions")
public class AuthSession extends BaseEntity {

    @Column(name = "principal_uid", nullable = false)
    private UUID principalUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, length = 32)
    private PrincipalType principalType;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    /** Null while live. Set the moment the session is ended early. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 32)
    private SessionRevocationReason revokedReason;

    protected AuthSession() {
    }

    private AuthSession(UUID principalUid, PrincipalType principalType, Instant openedAt, Instant expiresAt) {
        this.principalUid = principalUid;
        this.principalType = principalType;
        this.expiresAt = expiresAt;
        this.lastUsedAt = openedAt;
    }

    /**
     * Open a session for a principal.
     *
     * <p>Takes the expiry rather than computing it, so the one place that decides how long a session lives is
     * the service holding the configured lifetime — not this constructor, where a default would harden into a
     * policy nobody chose.
     */
    public static AuthSession open(UUID principalUid, PrincipalType principalType, Instant openedAt,
            Instant expiresAt) {
        return new AuthSession(principalUid, principalType, openedAt, expiresAt);
    }

    /**
     * Whether the session may still be renewed at the given instant.
     *
     * <p>Both conditions in one method, so no caller can check revocation and forget expiry — a revoked
     * session and a lapsed one are the same answer to a refresh, and keeping them together here is what makes
     * that easy to honour.
     */
    public boolean isLiveAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    /** Record that the session was just used to mint a token. Does not extend {@link #expiresAt}. */
    public void touch(Instant now) {
        this.lastUsedAt = now;
    }

    /**
     * End the session early.
     *
     * <p>Idempotent: revoking an already-revoked session keeps the first reason and moment, because "when did
     * this stop, and what stopped it" must not be overwritten by a later, redundant revocation of the same
     * dead session — a logout that follows a suspension should not rewrite the suspension out of the trail.
     */
    public void revoke(Instant now, SessionRevocationReason reason) {
        if (this.revokedAt == null) {
            this.revokedAt = now;
            this.revokedReason = reason;
        }
    }
}
