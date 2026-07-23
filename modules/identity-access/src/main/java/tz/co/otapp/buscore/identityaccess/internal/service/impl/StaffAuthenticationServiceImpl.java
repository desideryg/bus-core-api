package tz.co.otapp.buscore.identityaccess.internal.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tz.co.otapp.buscore.apicontracts.error.ApiException;
import tz.co.otapp.buscore.identityaccess.Principal;
import tz.co.otapp.buscore.identityaccess.PrincipalContext;
import tz.co.otapp.buscore.identityaccess.PrincipalType;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.LoginRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.LoginResponse;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.RefreshRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.StaffView;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffCredential;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffIdentity;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.AuthEventType;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.IdentityErrors;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.SessionRevocationReason;
import tz.co.otapp.buscore.identityaccess.internal.repository.StaffCredentialRepository;
import tz.co.otapp.buscore.identityaccess.internal.repository.StaffIdentityRepository;
import tz.co.otapp.buscore.identityaccess.internal.repository.StaffOperatorRepository;
import tz.co.otapp.buscore.identityaccess.internal.repository.StaffRoleRepository;
import tz.co.otapp.buscore.identityaccess.internal.security.JwtService;
import tz.co.otapp.buscore.identityaccess.internal.service.AuthAuditRecorder;
import tz.co.otapp.buscore.identityaccess.internal.service.AuthSessionService;
import tz.co.otapp.buscore.identityaccess.internal.service.StaffAuthenticationService;
import tz.co.otapp.buscore.shared.logging.LogSanitizer;
import tz.co.otapp.buscore.shared.time.Times;

/**
 * Sign-in, where the ordering of the checks <em>is</em> the security property.
 *
 * <h2>Two rules govern this whole class</h2>
 *
 * <p><b>1 — The identifier is never an oracle.</b> An unknown identifier, a wrong password and a
 * non-active account all leave by the same door with the same code. Anything that distinguishes them turns
 * the sign-in endpoint into a free tool for discovering which accounts exist.
 *
 * <p><b>2 — Failure side effects must survive the rejection.</b> The failure counter is incremented and
 * then an exception is thrown. A plain {@code @Transactional} rolls that increment back along with the
 * throw, so the counter never advances, the lockout never triggers, and <b>nothing anywhere fails</b> —
 * the feature is simply absent. {@code noRollbackFor} is what prevents it, and it is the least obvious
 * line in this file.
 */
@RequiredArgsConstructor
@Service
@Transactional(noRollbackFor = ApiException.class)
@Slf4j
public class StaffAuthenticationServiceImpl implements StaffAuthenticationService {

    private final StaffIdentityRepository identities;
    private final StaffCredentialRepository credentials;
    private final StaffRoleRepository staffRoles;
    private final StaffOperatorRepository staffOperators;
    private final AuthAuditRecorder auditRecorder;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthSessionService authSessions;
    private final PrincipalContext principalContext;

    @Override
    public LoginResponse login(LoginRequest request) {
        Instant now = Times.now();
        String identifier = request.identifier().trim();

        Optional<StaffIdentity> found =
                identities.findByUsernameIgnoreCaseOrEmailIgnoreCase(identifier, identifier);

        if (found.isEmpty()) {
            // Hash anyway, against a value that cannot match. Returning early here would make an unknown
            // username measurably faster than a wrong password, and that timing difference is the same
            // oracle the identical error code exists to close.
            passwordEncoder.matches(request.password(), NO_SUCH_ACCOUNT_HASH);
            log.info("sign-in refused: no such identifier '{}'", LogSanitizer.clean(identifier, 128));
            // The row with no principal. A run of these against 'admin', 'root', 'administrator' is what a
            // spray looks like, and it is invisible if only resolved accounts are recorded.
            auditRecorder.recordUnknownAccount(AuthEventType.LOGIN_FAILURE, identifier);
            throw invalidCredentials();
        }

        StaffIdentity identity = found.get();
        StaffCredential credential = credentials.findByStaffIdentity(identity)
                .orElseThrow(() -> {
                    // An identity with no credential row cannot sign in and is a provisioning bug, not a
                    // caller error — logged as such, but reported as an ordinary refusal.
                    log.warn("staff identity {} has no credential row", identity.getUid());
                    return invalidCredentials();
                });

        // LOCKOUT IS CHECKED BEFORE THE PASSWORD, and that order is the whole point: a lock that still
        // evaluates the password stops nothing, because the attacker's last guess is the one that matters.
        if (credential.isLockedAt(now)) {
            log.info("sign-in refused: account {} is locked until {}", identity.getUid(), credential.getLockedUntil());
            audit(AuthEventType.LOGIN_BLOCKED_BY_LOCKOUT, identity, identifier);
            throw new ApiException(IdentityErrors.ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(request.password(), credential.getPasswordHash())) {
            credential.recordFailure(now);
            // This write must commit even though the next line throws — see the class javadoc.
            credentials.save(credential);
            log.info("sign-in refused: wrong password for {} (failures now {})",
                    identity.getUid(), credential.getFailedAttempts());
            audit(AuthEventType.LOGIN_FAILURE, identity, identifier);
            // A separate event, because "the fifth failure" and "the moment it locked" are different
            // questions and an investigation asks the second one.
            if (credential.isLockedAt(now)) {
                audit(AuthEventType.ACCOUNT_LOCKED, identity, identifier);
            }
            throw invalidCredentials();
        }

        // Checked AFTER the password, so a suspended account is indistinguishable from a wrong password to
        // anyone who does not already know the password.
        if (!identity.canAuthenticate()) {
            log.info("sign-in refused: account {} has status {}", identity.getUid(), identity.getStatus());
            audit(AuthEventType.LOGIN_FAILURE, identity, identifier);
            throw invalidCredentials();
        }

        credential.recordSuccess();
        credentials.save(credential);

        if (credential.isMustChangePassword()) {
            // Deliberately no token. Issuing one "so the change endpoint can be called" would make the
            // account fully usable while nominally requiring a rotation.
            audit(AuthEventType.PASSWORD_CHANGE_REQUIRED, identity, identifier);
            throw new ApiException(IdentityErrors.PASSWORD_CHANGE_REQUIRED);
        }

        JwtService.IssuedToken token = jwtService.issue(resolvePrincipal(identity));
        // The session opens only now, past every refusal above — a locked, wrong-password or rotation-pending
        // sign-in must not leave a live session behind the token it was denied.
        AuthSessionService.IssuedRefresh session = authSessions.open(identity.getUid(), PrincipalType.STAFF);
        audit(AuthEventType.LOGIN_SUCCESS, identity, identifier);
        log.info("sign-in succeeded for {}", identity.getUid());
        return new LoginResponse(token.token(), token.expiresAt(), identity.getDisplayName(),
                session.refreshToken(), session.expiresAt());
    }

    @Override
    public LoginResponse refresh(RefreshRequest request) {
        // Rotate first: this proves the token names a live session, spends it, and — on a replayed spent
        // token — revokes the session and throws before we ever touch the account.
        AuthSessionService.RotatedRefresh rotated =
                authSessions.rotate(request.refreshToken(), PrincipalType.STAFF);

        StaffIdentity identity = identities.findByUid(rotated.principalUid())
                .filter(StaffIdentity::canAuthenticate)
                .orElseThrow(() -> {
                    // The account was withdrawn or removed under a session that was still refreshing. Its
                    // successor token was just minted and would otherwise keep working, so end every session
                    // it holds rather than only refusing this renewal. The revocation survives the throw —
                    // see the class note on noRollbackFor.
                    authSessions.revokeAllFor(rotated.principalUid(), PrincipalType.STAFF,
                            SessionRevocationReason.ACCOUNT_WITHDRAWN);
                    log.info("refresh refused: {} can no longer authenticate", rotated.principalUid());
                    return new ApiException(IdentityErrors.REFRESH_TOKEN_INVALID);
                });

        // Authority re-resolved against the account as it is now, not as it was at sign-in — this is what
        // makes a role or membership change take effect within a refresh cycle rather than never.
        JwtService.IssuedToken token = jwtService.issue(resolvePrincipal(identity));
        audit(AuthEventType.TOKEN_REFRESHED, identity, identity.getUsername());
        log.info("refreshed session for {}", identity.getUid());
        return new LoginResponse(token.token(), token.expiresAt(), identity.getDisplayName(),
                rotated.refreshToken(), rotated.expiresAt());
    }

    @Override
    public void logout(RefreshRequest request) {
        authSessions.logout(request.refreshToken(), PrincipalType.STAFF);
    }

    /**
     * The authority a staff token carries — resolved identically for a sign-in and a refresh.
     *
     * <p>Both are snapshots, correct at issue and carried in the token; the payoff of resolving it again on
     * every refresh is that the snapshot is never more than one refresh cycle stale. Archived roles are
     * excluded by the query, so archiving a role withdraws it rather than only blocking future grants;
     * platform staff legitimately resolve to no operators, being scoped by their tenancy instead.
     */
    private Principal resolvePrincipal(StaffIdentity identity) {
        Set<String> permissions = staffRoles.findPermissionCodes(identity);
        List<UUID> operatorUids = staffOperators.findOperatorUids(identity);
        return new Principal(identity.getUid(), PrincipalType.STAFF, identity.getTenancy(), operatorUids,
                permissions);
    }

    @Override
    @Transactional(readOnly = true)
    public StaffView currentStaff() {
        Principal principal = principalContext.require();
        return identities.findByUid(principal.uid())
                .map(StaffView::of)
                // A valid token naming an account that no longer exists. Rare, and precisely the case a
                // lookup must not silently return an empty body for.
                .orElseThrow(() -> new ApiException(IdentityErrors.NOT_AUTHENTICATED));
    }

    private void audit(AuthEventType eventType, StaffIdentity identity, String identifierUsed) {
        auditRecorder.record(eventType, PrincipalType.STAFF, identity.getUid(), identifierUsed);
    }

    private static ApiException invalidCredentials() {
        return new ApiException(IdentityErrors.INVALID_CREDENTIALS);
    }

    /**
     * A well-formed hash of a value nobody knows, used to spend the same time verifying a password for an
     * identifier that does not exist as for one that does.
     *
     * <p><b>The {@code {bcrypt}} prefix is required, not decorative.</b> The encoder is a
     * {@code DelegatingPasswordEncoder}, which reads the algorithm out of the stored hash — given a bare
     * {@code $2a$…} it finds no algorithm id and <em>throws</em> rather than returning false. That turned
     * this timing-equalisation step into a 500 for every unknown identifier, which the integration test
     * caught: an endpoint whose error response distinguishes an unknown user is exactly the oracle this
     * constant exists to close.
     *
     * <p>It must also remain a real hash at the configured strength. A placeholder like {@code "x"} would
     * be rejected in microseconds and reintroduce the timing difference by another route.
     */
    private static final String NO_SUCH_ACCOUNT_HASH =
            "{bcrypt}$2a$12$C6UzMDM.H6dfI/f/IKcEe.3vLtu0LWVjO/AtoKZI3fVJvVYQMB0Vy";
}
