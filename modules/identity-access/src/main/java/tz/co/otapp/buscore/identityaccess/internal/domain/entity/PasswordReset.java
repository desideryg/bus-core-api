package tz.co.otapp.buscore.identityaccess.internal.domain.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Getter;
import tz.co.otapp.buscore.shared.abstraction.BaseEntity;

/**
 * A one-time permission to set an account's password.
 *
 * <p>It exists so that provisioning an account and knowing its password stay separate. If an administrator
 * typed the first password instead, every account's initial credential would be known to somebody else and
 * the audit trail could never distinguish the holder's actions from theirs.
 *
 * <p>The same row type serves a forgotten password, so there is one path rather than two that drift.
 *
 * <h2>The row holds a hash, never the token</h2>
 *
 * <p>A reset token is a bearer credential — whoever holds it can take the account. Storing it in the clear
 * would mean read access to this table is equivalent to the password of every account with a live reset.
 * The token itself exists exactly once, in the response that created it, and is never recoverable
 * afterwards; if it is lost the remedy is to issue another.
 */
@Getter
@Entity
@Table(name = "staff_password_resets")
public class PasswordReset extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_identity_id", nullable = false)
    private StaffIdentity staffIdentity;

    /** Hex of SHA-256. Deterministic on purpose — see the migration; a redemption can only look up. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Null until redeemed. Kept rather than deleted, because "was it used, and when" is the question. */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** The administrator who caused it to exist. Where a takeover investigation starts. */
    @Column(name = "issued_by_uid", nullable = false)
    private UUID issuedByUid;

    protected PasswordReset() {
    }

    private PasswordReset(StaffIdentity staffIdentity, String tokenHash, Instant expiresAt, UUID issuedByUid) {
        this.staffIdentity = staffIdentity;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.issuedByUid = issuedByUid;
    }

    public static PasswordReset of(StaffIdentity staffIdentity, String tokenHash, Instant expiresAt,
            UUID issuedByUid) {
        return new PasswordReset(staffIdentity, tokenHash, expiresAt, issuedByUid);
    }

    /**
     * Whether this reset can still be redeemed at the given instant.
     *
     * <p>Both conditions in one method, so no caller can check expiry and forget consumption. A reset that
     * is merely expired and a reset that was already used are the same answer to a caller — see
     * {@code RESET_TOKEN_INVALID} — and keeping them together here is what makes that easy to honour.
     */
    public boolean isRedeemableAt(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    /**
     * Spend it.
     *
     * <p>Idempotence is not wanted here: a second redemption of the same token must fail, which is what
     * {@link #isRedeemableAt} enforces on the way in. Marking rather than deleting also frees the partial
     * unique index, so a later reset can be issued.
     */
    public void consume(Instant now) {
        this.consumedAt = now;
    }
}
