package tz.co.otapp.buscore.identityaccess.internal.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tz.co.otapp.buscore.apicontracts.error.ApiException;
import tz.co.otapp.buscore.identityaccess.Principal;
import tz.co.otapp.buscore.identityaccess.PrincipalType;
import tz.co.otapp.buscore.identityaccess.internal.domain.Msisdn;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.AgentLoginRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.LoginResponse;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.RefreshRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.AgentCredential;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.AgentIdentity;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.AuthEventType;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.IdentityErrors;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.SessionRevocationReason;
import tz.co.otapp.buscore.identityaccess.internal.repository.AgentCredentialRepository;
import tz.co.otapp.buscore.identityaccess.internal.repository.AgentIdentityRepository;
import tz.co.otapp.buscore.identityaccess.internal.security.JwtService;
import tz.co.otapp.buscore.identityaccess.internal.security.PinEncoder;
import tz.co.otapp.buscore.identityaccess.internal.service.AgentAuthenticationService;
import tz.co.otapp.buscore.identityaccess.internal.service.AuthAuditRecorder;
import tz.co.otapp.buscore.identityaccess.internal.service.AuthSessionService;
import tz.co.otapp.buscore.shared.logging.LogSanitizer;
import tz.co.otapp.buscore.shared.time.Times;

/**
 * Agent sign-in.
 *
 * <p>The <b>order of the checks is the security property</b>, and it is the same order staff sign-in uses,
 * for the same reasons. Where this class differs from {@link StaffAuthenticationServiceImpl} it differs
 * because a PIN is not a password — and every such difference is marked.
 *
 * <h2>Three rules govern the whole class</h2>
 *
 * <p><b>1 — The number is never an oracle.</b> An unknown number, a <em>malformed</em> number, a wrong PIN
 * and a non-active account all leave by the same door with the same code and the same cost. This matters
 * more here than it does for staff: a staff username has to be guessed, whereas <b>a phone number is
 * dialled</b>. A login that distinguished "no such agent" from "wrong PIN" would turn any contact list, or
 * any block of numbers, into a directory of who sells tickets — and those people carry float.
 *
 * <p><b>2 — Failure side effects must survive the rejection.</b> {@code noRollbackFor} keeps the failure
 * counter's increment when the refusal is thrown. Without it the counter never advances, the lockout never
 * triggers, and nothing anywhere fails: the defence is simply absent. On a six-digit secret that is the
 * difference between "unguessable" and "guessable in a fortnight".
 *
 * <p><b>3 — The token asserts identity and nothing else.</b> An agent's permission set is empty by
 * construction and permanently so; what they may sell lives in the {@code agent} module, four waves later.
 * Anything here that populated permissions would be inventing authority.
 */
@RequiredArgsConstructor
@Service
@Transactional(noRollbackFor = ApiException.class)
@Slf4j
public class AgentAuthenticationServiceImpl implements AgentAuthenticationService {

    private final AgentIdentityRepository identities;
    private final AgentCredentialRepository credentials;
    private final AuthAuditRecorder auditRecorder;
    private final PinEncoder pinEncoder;
    private final JwtService jwtService;
    private final AuthSessionService authSessions;

    @Override
    public LoginResponse login(AgentLoginRequest request) {
        Instant now = Times.now();

        // Canonicalised before anything else, because the stored form is canonical and a lookup by the
        // typed form would find nothing for a number that plainly exists.
        String msisdn = Msisdn.canonical(request.msisdn());

        // A MALFORMED NUMBER IS TREATED AS AN UNKNOWN ONE, deliberately. Reporting "that is not a valid
        // phone number" would tell a caller which of their guesses were even plausible, which is a cheap
        // way to narrow a search before starting it. Note it still pays the hashing cost below.
        Optional<AgentIdentity> found = msisdn == null
                ? Optional.empty()
                : identities.findByMsisdn(msisdn);

        if (found.isEmpty()) {
            // Hash anyway, against a value that cannot match. Returning early would make an unknown or
            // malformed number measurably faster than a wrong PIN, and that timing difference is the same
            // oracle the identical error code exists to close.
            pinEncoder.matches(request.pin(), NO_SUCH_ACCOUNT_HASH);
            log.info("agent sign-in refused: no such number '{}'",
                    LogSanitizer.clean(request.msisdn(), 32));
            // The row with no principal. A run of these across a block of numbers is what enumeration
            // looks like, and it is invisible if only resolved accounts are recorded.
            auditRecorder.recordUnknownAccount(AuthEventType.LOGIN_FAILURE,
                    msisdn == null ? request.msisdn() : msisdn);
            throw invalidCredentials();
        }

        AgentIdentity identity = found.get();
        AgentCredential credential = credentials.findByAgentIdentity(identity)
                .orElseThrow(() -> {
                    // An identity with no credential cannot sign in. A provisioning bug, not a caller
                    // error — logged as such, reported as an ordinary refusal.
                    log.warn("agent identity {} has no credential row", identity.getUid());
                    return invalidCredentials();
                });

        // LOCKOUT IS CHECKED BEFORE THE PIN, and that order is the whole point: a lock that still evaluates
        // the presented PIN stops nothing, because the attacker's last guess is the one that matters.
        if (credential.isLockedAt(now)) {
            log.info("agent sign-in refused: {} is locked until {}",
                    identity.getUid(), credential.getLockedUntil());
            audit(AuthEventType.LOGIN_BLOCKED_BY_LOCKOUT, identity);
            throw new ApiException(IdentityErrors.ACCOUNT_LOCKED);
        }

        if (!pinEncoder.matches(request.pin(), credential.getPinHash())) {
            credential.recordFailure(now);
            // This write must commit even though the next lines throw — see the class javadoc.
            credentials.save(credential);
            log.info("agent sign-in refused: wrong PIN for {} (failures now {})",
                    identity.getUid(), credential.getFailedAttempts());
            audit(AuthEventType.LOGIN_FAILURE, identity);
            // A separate event, because "the third failure" and "the moment it locked" are different
            // questions and an investigation asks the second one.
            if (credential.isLockedAt(now)) {
                audit(AuthEventType.ACCOUNT_LOCKED, identity);
            }
            throw invalidCredentials();
        }

        // Checked AFTER the PIN, so a suspended agent is indistinguishable from a wrong PIN to anyone who
        // does not already know the PIN.
        if (!identity.canAuthenticate()) {
            log.info("agent sign-in refused: {} has status {}", identity.getUid(), identity.getStatus());
            audit(AuthEventType.LOGIN_FAILURE, identity);
            throw invalidCredentials();
        }

        credential.recordSuccess();
        credentials.save(credential);

        if (credential.isMustChangePin()) {
            // Deliberately no token, exactly as for a staff password. Issuing one "so the change endpoint
            // can be called" would make the account fully usable while nominally requiring a change.
            //
            // The route that completes this arrives with agent provisioning — nothing in this slice can set
            // the flag, so nothing in this slice can strand an agent behind it.
            audit(AuthEventType.PASSWORD_CHANGE_REQUIRED, identity);
            throw new ApiException(IdentityErrors.PIN_CHANGE_REQUIRED);
        }

        JwtService.IssuedToken token = jwtService.issue(agentPrincipal(identity));
        // Opened only here, past every refusal above — a locked, wrong-PIN or change-required sign-in must
        // not leave a live session behind the token it was denied.
        AuthSessionService.IssuedRefresh session = authSessions.open(identity.getUid(), PrincipalType.AGENT);

        audit(AuthEventType.LOGIN_SUCCESS, identity);
        log.info("agent sign-in succeeded for {}", identity.getUid());
        return new LoginResponse(token.token(), token.expiresAt(), identity.getDisplayName(),
                session.refreshToken(), session.expiresAt());
    }

    @Override
    public LoginResponse refresh(RefreshRequest request) {
        // Rotate first — proves the token names a live session, spends it, and revokes the session on a
        // replayed spent token, all before the login is touched.
        AuthSessionService.RotatedRefresh rotated =
                authSessions.rotate(request.refreshToken(), PrincipalType.AGENT);

        AgentIdentity identity = identities.findByUid(rotated.principalUid())
                .filter(AgentIdentity::canAuthenticate)
                .orElseThrow(() -> {
                    // The login was withdrawn under a session still refreshing. Its successor token was just
                    // minted, so end every session it holds rather than only refusing this one. The
                    // revocation survives the throw — see the class note on noRollbackFor.
                    authSessions.revokeAllFor(rotated.principalUid(), PrincipalType.AGENT,
                            SessionRevocationReason.ACCOUNT_WITHDRAWN);
                    log.info("agent refresh refused: {} can no longer authenticate", rotated.principalUid());
                    return new ApiException(IdentityErrors.REFRESH_TOKEN_INVALID);
                });

        JwtService.IssuedToken token = jwtService.issue(agentPrincipal(identity));
        audit(AuthEventType.TOKEN_REFRESHED, identity);
        log.info("agent refreshed session for {}", identity.getUid());
        return new LoginResponse(token.token(), token.expiresAt(), identity.getDisplayName(),
                rotated.refreshToken(), rotated.expiresAt());
    }

    @Override
    public void logout(RefreshRequest request) {
        authSessions.logout(request.refreshToken(), PrincipalType.AGENT);
    }

    /**
     * The principal an agent token carries: identity, and deliberately nothing else.
     *
     * <p>NO TENANCY, NO OPERATORS, NO PERMISSIONS — and none of the three is a gap to be filled later. An
     * agent belongs to no staff tenancy; the operators they sell for are selling grants in another module;
     * and their authority is those grants, never this module's permission catalog. The empty sets here are
     * the honest answer, enforced by {@link Principal}'s own constructor, and it is why re-resolving on
     * refresh reads nothing — there is nothing to read.
     */
    private static Principal agentPrincipal(AgentIdentity identity) {
        return new Principal(identity.getUid(), PrincipalType.AGENT, null, List.of(), Set.of());
    }

    /**
     * Record against the agent, using the handle the rest of the system knows them by.
     *
     * <p>The uid recorded is the login's own, which is also what the {@code agent} module keys its selling
     * grants on — so an investigation starting there, at a float discrepancy or a disputed sale, joins to
     * the sign-ins that preceded it without a lookup only this module could perform.
     */
    private void audit(AuthEventType eventType, AgentIdentity identity) {
        auditRecorder.record(eventType, PrincipalType.AGENT, identity.getUid(), identity.getMsisdn());
    }

    private ApiException invalidCredentials() {
        return new ApiException(IdentityErrors.INVALID_CREDENTIALS);
    }

    /**
     * A valid bcrypt hash of a value nobody will present, so verifying against a non-existent agent costs
     * the same as verifying against a real one.
     *
     * <p>The {@code {bcrypt}} prefix is load-bearing: without it the delegating encoder throws rather than
     * returning false, which turns the timing equaliser into a 500 that distinguishes the case perfectly.
     */
    private static final String NO_SUCH_ACCOUNT_HASH =
            "{bcrypt}$2a$12$C6UzMDM.H6dfI/f/IKcEe.3vLtu0LWVjO/AtoKZI3fVJvVYQMB0Vy";
}
