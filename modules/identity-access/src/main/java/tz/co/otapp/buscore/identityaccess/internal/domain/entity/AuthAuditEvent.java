package tz.co.otapp.buscore.identityaccess.internal.domain.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import tz.co.otapp.buscore.identityaccess.PrincipalType;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.AuthEventType;
import tz.co.otapp.buscore.shared.abstraction.BaseEntity;
import tz.co.otapp.buscore.shared.logging.LogSanitizer;

/**
 * One thing that happened on the authentication path.
 *
 * <h2>The principal is nullable, and that is the design</h2>
 *
 * <p>A failed sign-in against a username that does not exist has no principal to name — and that row is
 * precisely the one worth having, because a run of them against 'admin', 'administrator', 'root' is what a
 * spray looks like. A non-null constraint would force the recording code to invent an identifier or skip
 * the event, and it would skip it.
 *
 * <h2>What is stored, and what must never be</h2>
 *
 * <p>The identifier the caller <em>typed</em> is stored. The password is not, obviously — but neither is
 * anything derived from it, not even a hash: a trail is read by people who are allowed to see what
 * happened, not what was presented.
 *
 * <p>The identifier and user agent are caller-controlled, so both are sanitised before storage. They are
 * later rendered into logs and admin screens, and a value containing a newline can forge a log entry.
 */
@Entity
@Table(name = "auth_audit_events")
public class AuthAuditEvent extends BaseEntity {

    /** Null when the attempt named nobody who exists. */
    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", length = 32)
    private PrincipalType principalType;

    /** Null for the same reason. */
    @Column(name = "principal_uid")
    private UUID principalUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private AuthEventType eventType;

    @Column(name = "identifier_used", length = 128)
    private String identifierUsed;

    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    protected AuthAuditEvent() {
    }

    private AuthAuditEvent(AuthEventType eventType, PrincipalType principalType, UUID principalUid,
            String identifierUsed, String sourceIp, String userAgent) {
        this.eventType = eventType;
        this.principalType = principalType;
        this.principalUid = principalUid;
        // Sanitised at the boundary rather than at each render. A value that has been through a database
        // is exactly as clean as it was when it went in.
        this.identifierUsed = identifierUsed == null ? null : LogSanitizer.clean(identifierUsed, 128);
        this.sourceIp = sourceIp;
        this.userAgent = userAgent == null ? null : LogSanitizer.clean(userAgent, 255);
    }

    /** An event about somebody known. */
    public static AuthAuditEvent forPrincipal(AuthEventType eventType, PrincipalType principalType, UUID principalUid,
            String identifierUsed, String sourceIp, String userAgent) {
        return new AuthAuditEvent(eventType, principalType, principalUid, identifierUsed, sourceIp, userAgent);
    }

    /**
     * An event about an attempt that named nobody who exists.
     *
     * <p>A separate factory rather than nulls at the call site, so recording an unknown-account attempt is
     * as easy as recording a known one — the moment it is harder, it stops happening.
     */
    public static AuthAuditEvent forUnknownAccount(AuthEventType eventType, String identifierUsed, String sourceIp,
            String userAgent) {
        return new AuthAuditEvent(eventType, null, null, identifierUsed, sourceIp, userAgent);
    }

    public AuthEventType getEventType() {
        return eventType;
    }

    public PrincipalType getPrincipalType() {
        return principalType;
    }

    public UUID getPrincipalUid() {
        return principalUid;
    }

    public String getIdentifierUsed() {
        return identifierUsed;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    /**
     * When it happened.
     *
     * <p>{@code createdAt} from the base entity, not a column of its own. For an append-only event the two
     * would be the same moment, and two columns holding one fact is two columns that can disagree.
     */
    public Instant occurredAt() {
        return getCreatedAt();
    }
}
