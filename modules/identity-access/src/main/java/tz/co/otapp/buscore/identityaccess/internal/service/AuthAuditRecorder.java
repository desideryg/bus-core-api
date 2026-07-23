package tz.co.otapp.buscore.identityaccess.internal.service;

import java.util.UUID;

import tz.co.otapp.buscore.identityaccess.PrincipalType;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.AuthEventType;

/**
 * Records what happened on the authentication path.
 *
 * <p>Every method returns void and none of them throws. <b>A trail that can fail a request is a trail that
 * gets removed from the request path</b> the first time it causes an outage — and then there is no trail at
 * all. Recording is best-effort by design; the request it describes has already been decided.
 */
public interface AuthAuditRecorder {

    /** An event about a known account. */
    void record(AuthEventType eventType, PrincipalType principalType, UUID principalUid, String identifierUsed);

    /**
     * An event about an attempt that named nobody who exists.
     *
     * <p>These rows are the reason the principal columns are nullable. A run of them against 'admin',
     * 'administrator', 'root' is what a spray looks like, and it is invisible if only resolved accounts
     * are recorded.
     */
    void recordUnknownAccount(AuthEventType eventType, String identifierUsed);
}
