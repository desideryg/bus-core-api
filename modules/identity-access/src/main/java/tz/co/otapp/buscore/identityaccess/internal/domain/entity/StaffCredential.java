package tz.co.otapp.buscore.identityaccess.internal.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import tz.co.otapp.buscore.shared.abstraction.BaseEntity;
import tz.co.otapp.buscore.shared.time.Times;

/**
 * The password material for one {@link StaffIdentity}, and the state that governs failed attempts.
 *
 * <p>Split from the identity so a query that reads who somebody is never loads a password hash it has no
 * use for. The database enforces one credential per identity with a unique foreign key; without it, two
 * live passwords could exist for one account with only one of them subject to lockout.
 *
 * <h2>Lockout lives here, and the ordering is the security property</h2>
 *
 * <p>{@link #isLockedAt} must be consulted <b>before</b> the password is verified. A lock that still
 * evaluates the presented password stops nothing — the attacker's last guess is the one that matters, and
 * a lock that lets a correct password through is not a lock.
 */
@Entity
@Table(name = "staff_credentials")
public class StaffCredential extends BaseEntity {

    /** How many consecutive failures trigger a lockout. */
    public static final int MAX_FAILED_ATTEMPTS = 5;

    /** How long a lockout lasts. Long enough to make guessing impractical, short enough to self-heal. */
    private static final int LOCKOUT_MINUTES = 15;

    /**
     * A real association, not a bare identifier column.
     *
     * <p>LAZY because the identity is often already loaded when this row is fetched, and eager loading
     * would issue a second query for a row the caller already holds.
     */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_identity_id", nullable = false)
    private StaffIdentity staffIdentity;

    /** The hash. Never the password, and never logged — see the deliberate absence of a toString here. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "password_updated_at", nullable = false)
    private Instant passwordUpdatedAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    /** Null when not locked. A past instant means a lockout that has since expired. */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    /**
     * Forces a password change at next login.
     *
     * <p>Present from the start because the bootstrap identity is created with a configured password that
     * somebody has necessarily seen, and an account that cannot be made to rotate it is an account whose
     * first password is permanent.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    protected StaffCredential() {
    }

    private StaffCredential(StaffIdentity staffIdentity, String passwordHash, boolean mustChangePassword) {
        this.staffIdentity = staffIdentity;
        this.passwordHash = passwordHash;
        this.passwordUpdatedAt = Times.now();
        this.mustChangePassword = mustChangePassword;
    }

    public static StaffCredential of(StaffIdentity staffIdentity, String passwordHash, boolean mustChangePassword) {
        return new StaffCredential(staffIdentity, passwordHash, mustChangePassword);
    }

    /**
     * Whether the account is locked at the given instant.
     *
     * <p>Takes the instant rather than reading the clock itself, so the caller decides the moment being
     * asked about and a test can ask about any of them.
     */
    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /**
     * Record a failed attempt, locking the account once the threshold is reached.
     *
     * <p>This write must survive the rejection that follows it. The caller's transaction is configured not
     * to roll back on the refusal exception — without that, the counter is discarded along with the throw
     * and the lockout silently never happens.
     */
    public void recordFailure(Instant now) {
        failedAttempts++;
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            lockedUntil = now.plusSeconds(LOCKOUT_MINUTES * 60L);
        }
    }

    /** Clear the counter after a successful login, so failures must be consecutive to lock an account. */
    public void recordSuccess() {
        failedAttempts = 0;
        lockedUntil = null;
    }

    public StaffIdentity getStaffIdentity() {
        return staffIdentity;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }
}
