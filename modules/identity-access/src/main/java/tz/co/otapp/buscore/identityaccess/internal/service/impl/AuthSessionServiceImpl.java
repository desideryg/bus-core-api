package tz.co.otapp.buscore.identityaccess.internal.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tz.co.otapp.buscore.apicontracts.error.ApiException;
import tz.co.otapp.buscore.identityaccess.PrincipalType;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.AuthRefreshToken;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.AuthSession;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.AuthEventType;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.IdentityErrors;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.SessionRevocationReason;
import tz.co.otapp.buscore.identityaccess.internal.repository.AuthRefreshTokenRepository;
import tz.co.otapp.buscore.identityaccess.internal.repository.AuthSessionRepository;
import tz.co.otapp.buscore.identityaccess.internal.security.ResetTokens;
import tz.co.otapp.buscore.identityaccess.internal.service.AuthAuditRecorder;
import tz.co.otapp.buscore.identityaccess.internal.service.AuthSessionService;
import tz.co.otapp.buscore.shared.time.Times;

/**
 * Sessions and their rotating refresh tokens.
 *
 * <h2>{@code noRollbackFor}, and it is the same reason sign-in carries it</h2>
 *
 * <p>Reuse detection revokes a session and then <b>rejects the request</b>. A plain {@code @Transactional}
 * rolls the revocation back along with the rejection, so the stolen-token replay is refused this once and the
 * session it should have killed stays alive for the next one — the defence present in the code and absent in
 * effect. {@code noRollbackFor} keeps the revocation committed through the throw, and it matches the
 * annotation the authentication services already rely on, so this service joining their transaction does not
 * quietly reintroduce a rollback boundary they took care to avoid.
 *
 * <h2>It re-resolves nothing</h2>
 *
 * <p>Turning a principal uid back into a token is the caller's job — see {@link AuthSessionService}. This
 * class touches only the two session tables and the trail, which is what lets one implementation serve staff
 * and agents without knowing anything about either.
 */
@RequiredArgsConstructor
@Service
@Transactional(noRollbackFor = ApiException.class)
@Slf4j
public class AuthSessionServiceImpl implements AuthSessionService {

    private final AuthSessionRepository sessions;
    private final AuthRefreshTokenRepository refreshTokens;
    private final AuthAuditRecorder auditRecorder;

    /**
     * How long a session lives from the sign-in that opened it — an absolute cap, not a sliding one.
     *
     * <p>On the field rather than a constructor parameter so {@code @RequiredArgsConstructor} still applies;
     * {@code lombok.copyableAnnotations} carries it onto the generated parameter. Default 30 days: long
     * enough that a field agent is not re-entering a PIN daily, short enough that an abandoned session is not
     * a credential that works for a year.
     */
    @Value("${identity.jwt.refresh-token-ttl:PT720H}")
    private final Duration refreshTtl;

    // ─────────────────────────────── opening ───────────────────────────────

    @Override
    public IssuedRefresh open(UUID principalUid, PrincipalType type) {
        Instant now = Times.now();
        AuthSession session = sessions.save(AuthSession.open(principalUid, type, now, now.plus(refreshTtl)));

        String token = ResetTokens.mint();
        refreshTokens.save(AuthRefreshToken.issue(session, ResetTokens.fingerprint(token)));

        // The token is absent from this line and every other — a bearer credential does not belong in a log.
        log.info("opened session {} for {} {} (expires {})",
                session.getUid(), type, principalUid, session.getExpiresAt());
        return new IssuedRefresh(token, session.getExpiresAt());
    }

    // ─────────────────────────────── renewing ───────────────────────────────

    @Override
    public RotatedRefresh rotate(String rawRefreshToken, PrincipalType expectedType) {
        Instant now = Times.now();

        AuthRefreshToken presented = refreshTokens.findByTokenHash(ResetTokens.fingerprint(rawRefreshToken))
                .orElseThrow(() -> {
                    // Unknown: never existed, or so old it was pruned. Nothing to revoke and nothing to
                    // detect — the generic refusal an unknown reset token gets, for the same reason.
                    log.info("refresh refused: token unknown");
                    return refreshInvalid();
                });
        AuthSession session = presented.getSession();

        // WRONG SURFACE IS AN UNKNOWN TOKEN. A staff refresh token presented at the agent door, or the
        // reverse, is refused exactly as a forged one — the audience separation enforced on every ordinary
        // call must not have a gap at the one endpoint whose whole purpose is minting fresh access tokens.
        if (session.getPrincipalType() != expectedType) {
            log.info("refresh refused: {} token presented on {} surface",
                    session.getPrincipalType(), expectedType);
            throw refreshInvalid();
        }

        // REUSE. The presented token was already spent, so either the holder is replaying it or someone else
        // holds a copy — indistinguishable, so both are treated as the theft. Revoking the whole session puts
        // whoever still holds a live token back to a sign-in. This write must survive the throw; see the
        // class note on noRollbackFor.
        if (!presented.isLive()) {
            session.revoke(now, SessionRevocationReason.REFRESH_TOKEN_REUSED);
            sessions.save(session);
            audit(AuthEventType.REFRESH_TOKEN_REUSED, session);
            log.warn("refresh reuse detected on session {} for {} {} — session revoked",
                    session.getUid(), session.getPrincipalType(), session.getPrincipalUid());
            throw refreshInvalid();
        }

        // Revoked or lapsed. Not reuse — this token was never spent — just a session that has ended, and one
        // answer covers both so the refuser is not an oracle about which.
        if (!session.isLiveAt(now)) {
            log.info("refresh refused: session {} is not live", session.getUid());
            throw refreshInvalid();
        }

        // Spend the presented token and issue its successor. CONSUME, FLUSH, then INSERT — the partial unique
        // index admits one live token per session, and Hibernate is free to order the insert before the
        // update otherwise and trip it. The same ordering issueReset performs for staff_password_resets.
        presented.consume(now);
        refreshTokens.save(presented);
        refreshTokens.flush();

        String token = ResetTokens.mint();
        refreshTokens.save(AuthRefreshToken.issue(session, ResetTokens.fingerprint(token)));
        session.touch(now);
        sessions.save(session);

        log.info("rotated session {} for {} {}", session.getUid(), expectedType, session.getPrincipalUid());
        return new RotatedRefresh(session.getPrincipalUid(), token, session.getExpiresAt());
    }

    // ─────────────────────────────── ending ───────────────────────────────

    @Override
    public void logout(String rawRefreshToken, PrincipalType expectedType) {
        Instant now = Times.now();

        // Idempotent and silent. An unknown, wrong-surface or already-ended token is the state logout is
        // trying to reach — there is no live session left for it — so it is success, not a refusal, and a
        // public endpoint that answered otherwise would be an oracle about which tokens are real.
        refreshTokens.findByTokenHash(ResetTokens.fingerprint(rawRefreshToken))
                .map(AuthRefreshToken::getSession)
                .filter(session -> session.getPrincipalType() == expectedType)
                .filter(session -> session.isLiveAt(now))
                .ifPresent(session -> {
                    session.revoke(now, SessionRevocationReason.LOGOUT);
                    sessions.save(session);
                    audit(AuthEventType.LOGGED_OUT, session);
                    log.info("session {} ended by holder", session.getUid());
                });
    }

    @Override
    public void revokeAllFor(UUID principalUid, PrincipalType type, SessionRevocationReason reason) {
        Instant now = Times.now();

        List<AuthSession> live =
                sessions.findAllByPrincipalUidAndPrincipalTypeAndRevokedAtIsNull(principalUid, type);
        if (live.isEmpty()) {
            return;
        }

        live.forEach(session -> session.revoke(now, reason));
        sessions.saveAll(live);

        // One event for the act, not one per session: "every session for this account was ended, for this
        // reason" is the single thing that happened. The count goes to the log, where a number helps and does
        // not multiply the trail.
        auditRecorder.record(AuthEventType.SESSION_REVOKED, type, principalUid, reason.name());
        log.info("revoked {} session(s) for {} {} ({})", live.size(), type, principalUid, reason);
    }

    // ───────────────────────────────── helpers ─────────────────────────────────

    private void audit(AuthEventType eventType, AuthSession session) {
        auditRecorder.record(eventType, session.getPrincipalType(), session.getPrincipalUid(), null);
    }

    private static ApiException refreshInvalid() {
        return new ApiException(IdentityErrors.REFRESH_TOKEN_INVALID);
    }
}
