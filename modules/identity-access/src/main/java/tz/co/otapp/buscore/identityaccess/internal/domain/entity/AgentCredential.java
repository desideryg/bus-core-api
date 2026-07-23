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
import tz.co.otapp.buscore.shared.time.Times;

/**
 * An agent's PIN, and the state that governs failed attempts.
 *
 * <h2>A PIN is not a short password, and the lockout is where that shows</h2>
 *
 * <p>The staff threshold is 5 failures and a 15-minute lock. Applied to a PIN that is the whole defence:
 *
 * <pre>
 * 4 digits =    10,000 candidates | 5 per 15 min = 480/day  | exhausted in ~21 days, half of them on average
 * 6 digits = 1,000,000 candidates | 5 per 15 min = 480/day  | exhausted in ~5.7 years
 * </pre>
 *
 * <p>Three weeks of unattended guessing is not a defence, so this class uses {@link #MAX_FAILED_ATTEMPTS}
 * of 3 and a 30-minute lock — 144 attempts a day, which puts even a 4-digit PIN at ~69 days and a 6-digit
 * one beyond any horizon that matters:
 *
 * <pre>
 * 4 digits =    10,000 candidates | 3 per 30 min = 144/day | ~69 days
 * 6 digits = 1,000,000 candidates | 3 per 30 min = 144/day | ~19 years
 * </pre>
 *
 * <p><b>The arithmetic is the argument for six digits</b>, and enforcing that minimum belongs to whichever
 * slice first lets a PIN be set — there is no such surface yet, and a rule enforced nowhere is not a rule.
 * It is recorded here because this is where whoever writes that surface will be reading.
 *
 * <h2>What this does not defend against, stated plainly</h2>
 *
 * <p><b>The horizontal attack.</b> One guess of {@code 123456} against each of ten thousand agents locks
 * nobody out — every account sees a single failure — and if any one agent chose that PIN, it succeeds.
 * Per-account lockout is structurally incapable of seeing it, because the pattern is across accounts and
 * the counter is inside one. What sees it is a limit on attempts per source and an alert on the rate of
 * failures across the estate, which is <b>slice 8</b>. Until then this is exposed, and saying so is more
 * useful than a comment implying otherwise.
 *
 * <p><b>An offline attack on the hash.</b> The encoder is the same adaptive one used for passwords and it
 * buys much less here: a million candidates falls to any attacker holding this column, whatever the work
 * factor. The hash is still correct — it is simply not the defence.
 */
@Getter
@Entity
@Table(name = "agent_credentials")
public class AgentCredential extends BaseEntity {

    /**
     * Three, where staff get five.
     *
     * <p>A shorter secret must buy fewer guesses. The cost is a legitimate agent who mistypes twice and is
     * locked on the third — which is why the lock expires by itself rather than needing an administrator,
     * and why it is 30 minutes rather than a day.
     */
    public static final int MAX_FAILED_ATTEMPTS = 3;

    /** Double the staff lockout: it is doing twice the work, against a secret a fraction of the size. */
    private static final int LOCKOUT_MINUTES = 30;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_identity_id", nullable = false)
    private AgentIdentity agentIdentity;

    /** The hash. Never the PIN, and never logged — note the deliberate absence of a toString. */
    @Column(name = "pin_hash", nullable = false, length = 255)
    private String pinHash;

    @Column(name = "pin_updated_at", nullable = false)
    private Instant pinUpdatedAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    /** Null when not locked. A past instant is a lockout that has since expired. */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    /**
     * Forces a PIN change at next sign-in.
     *
     * <p>Present from the start because an agent provisioned with a PIN somebody else chose has an initial
     * secret that person has necessarily seen. The route that completes the change arrives with agent
     * provisioning — exactly as {@code must_change_password} shipped in slice 1 and was completed in
     * slice 6.
     */
    @Column(name = "must_change_pin", nullable = false)
    private boolean mustChangePin;

    protected AgentCredential() {
    }

    private AgentCredential(AgentIdentity agentIdentity, String pinHash, boolean mustChangePin) {
        this.agentIdentity = agentIdentity;
        this.pinHash = pinHash;
        this.pinUpdatedAt = Times.now();
        this.mustChangePin = mustChangePin;
    }

    public static AgentCredential of(AgentIdentity agentIdentity, String pinHash, boolean mustChangePin) {
        return new AgentCredential(agentIdentity, pinHash, mustChangePin);
    }

    /**
     * Whether the account is locked at the given instant.
     *
     * <p>Takes the instant rather than reading the clock, so the caller decides the moment being asked
     * about and a test can ask about any of them.
     */
    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /**
     * Record a failed attempt, locking once the threshold is reached.
     *
     * <p>This write must survive the rejection that follows it. The calling service is annotated
     * {@code noRollbackFor = ApiException.class}; without it the increment is discarded along with the
     * throw, the lockout never triggers, and <b>nothing anywhere fails</b> — the defence is simply absent.
     */
    public void recordFailure(Instant now) {
        failedAttempts++;
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            lockedUntil = now.plusSeconds(LOCKOUT_MINUTES * 60L);
        }
    }

    /** Clear the counter after a success, so failures must be consecutive to lock an account. */
    public void recordSuccess() {
        failedAttempts = 0;
        lockedUntil = null;
    }
}
