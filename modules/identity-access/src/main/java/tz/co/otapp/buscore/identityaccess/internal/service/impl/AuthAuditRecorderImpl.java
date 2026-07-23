package tz.co.otapp.buscore.identityaccess.internal.service.impl;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import tz.co.otapp.buscore.identityaccess.PrincipalType;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.AuthAuditEvent;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.AuthEventType;
import tz.co.otapp.buscore.identityaccess.internal.repository.AuthAuditEventRepository;
import tz.co.otapp.buscore.identityaccess.internal.service.AuthAuditRecorder;

/**
 * Writes the authentication trail in its own transaction.
 *
 * <h2>REQUIRES_NEW, and why it is not optional</h2>
 *
 * <p>The rows that matter most are written on paths that then <b>reject the request</b> — a failed
 * sign-in, a lockout. Joining the caller's transaction means the record of the failure is rolled back
 * along with the failure, and the trail is emptiest exactly when it is most wanted.
 *
 * <p>The sign-in service already carries {@code noRollbackFor}, so today it would survive either way. That
 * is not enough. <b>The reference implementation relied on that alone and had no independent transaction
 * anywhere</b>, which made the guarantee depend on every future caller remembering an annotation — and a
 * later refactor wrapping a caller in a plain {@code @Transactional} would have silently emptied the trail
 * with no compile error and no failing test.
 *
 * <p>A separate transaction moves the guarantee from the caller to here, where it can be reasoned about
 * once. The cost is a connection per event on the authentication path, which is a fair price for a record
 * that survives the thing it records.
 *
 * <h2>It never throws</h2>
 *
 * <p>Every failure is swallowed and logged. A trail that can fail a request is a trail that gets removed
 * from the request path the first time it causes an outage — and then there is no trail at all. The
 * request being described has already been decided; nothing here can change it.
 */
@Service
@Slf4j
public class AuthAuditRecorderImpl implements AuthAuditRecorder {

    private final AuthAuditEventRepository events;

    public AuthAuditRecorderImpl(AuthAuditEventRepository events) {
        this.events = events;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuthEventType eventType, PrincipalType principalType, UUID principalUid,
            String identifierUsed) {
        save(AuthAuditEvent.forPrincipal(eventType, principalType, principalUid, identifierUsed,
                sourceIp(), userAgent()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUnknownAccount(AuthEventType eventType, String identifierUsed) {
        save(AuthAuditEvent.forUnknownAccount(eventType, identifierUsed, sourceIp(), userAgent()));
    }

    private void save(AuthAuditEvent event) {
        try {
            events.save(event);
        } catch (RuntimeException recordingFailed) {
            // Deliberately swallowed. See "It never throws".
            log.error("failed to record auth event {}", event.getEventType(), recordingFailed);
        }
    }

    /**
     * The caller's address, taken from the current request.
     *
     * <p><b>{@code X-Forwarded-For} is honoured, and that is a decision with a condition attached.</b>
     * Behind a load balancer the socket address is the balancer's, so without it every row records the
     * same useless value. But the header is caller-supplied and trivially forged — so this is only
     * trustworthy if a proxy in front of the application <em>overwrites</em> it. Where nothing does, an
     * attacker can attribute their attempts to any address they like.
     *
     * <p>Recorded here rather than passed through every service signature, because a parameter threaded
     * through six methods for the benefit of one is a parameter that eventually gets passed null — which
     * is exactly how the reference implementation ended up with an entire population of audit rows whose
     * source address was null.
     */
    private static String sourceIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // The header is a comma-separated chain; the first entry is the original client.
            String first = forwarded.split(",")[0].trim();
            return first.length() > 45 ? first.substring(0, 45) : first;
        }
        return request.getRemoteAddr();
    }

    private static String userAgent() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getHeader("User-Agent");
    }

    /** Null outside a request — a bootstrap runner or a scheduled sweep has no caller. */
    private static HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
}
