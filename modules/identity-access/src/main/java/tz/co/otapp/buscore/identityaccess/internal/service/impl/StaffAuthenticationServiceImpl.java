package tz.co.otapp.buscore.identityaccess.internal.service.impl;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import tz.co.otapp.buscore.apicontracts.error.ApiException;
import tz.co.otapp.buscore.identityaccess.Principal;
import tz.co.otapp.buscore.identityaccess.PrincipalContext;
import tz.co.otapp.buscore.identityaccess.PrincipalType;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.LoginRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.LoginResponse;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.StaffView;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffCredential;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffIdentity;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.IdentityErrors;
import tz.co.otapp.buscore.identityaccess.internal.repository.StaffCredentialRepository;
import tz.co.otapp.buscore.identityaccess.internal.repository.StaffIdentityRepository;
import tz.co.otapp.buscore.identityaccess.internal.repository.StaffRoleRepository;
import tz.co.otapp.buscore.identityaccess.internal.security.JwtService;
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
@Service
@Transactional(noRollbackFor = ApiException.class)
@Slf4j
public class StaffAuthenticationServiceImpl implements StaffAuthenticationService {

    private final StaffIdentityRepository identities;
    private final StaffCredentialRepository credentials;
    private final StaffRoleRepository staffRoles;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final PrincipalContext principalContext;

    public StaffAuthenticationServiceImpl(StaffIdentityRepository identities, StaffCredentialRepository credentials,
            StaffRoleRepository staffRoles, PasswordEncoder passwordEncoder, JwtService jwtService,
            PrincipalContext principalContext) {
        this.identities = identities;
        this.credentials = credentials;
        this.staffRoles = staffRoles;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.principalContext = principalContext;
    }

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
            throw new ApiException(IdentityErrors.ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(request.password(), credential.getPasswordHash())) {
            credential.recordFailure(now);
            // This write must commit even though the next line throws — see the class javadoc.
            credentials.save(credential);
            log.info("sign-in refused: wrong password for {} (failures now {})",
                    identity.getUid(), credential.getFailedAttempts());
            throw invalidCredentials();
        }

        // Checked AFTER the password, so a suspended account is indistinguishable from a wrong password to
        // anyone who does not already know the password.
        if (!identity.canAuthenticate()) {
            log.info("sign-in refused: account {} has status {}", identity.getUid(), identity.getStatus());
            throw invalidCredentials();
        }

        credential.recordSuccess();
        credentials.save(credential);

        if (credential.isMustChangePassword()) {
            // Deliberately no token. Issuing one "so the change endpoint can be called" would make the
            // account fully usable while nominally requiring a rotation.
            throw new ApiException(IdentityErrors.PASSWORD_CHANGE_REQUIRED);
        }

        // Resolved once, here, and carried in the token. Archived roles are excluded by the query, so
        // archiving a role actually withdraws it rather than only blocking future grants.
        Set<String> permissions = staffRoles.findPermissionCodes(identity);
        JwtService.IssuedToken token = jwtService.issue(new Principal(
                identity.getUid(), PrincipalType.STAFF, identity.getTenancy(), permissions));
        log.info("sign-in succeeded for {}", identity.getUid());
        return new LoginResponse(token.token(), token.expiresAt(), identity.getDisplayName());
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
