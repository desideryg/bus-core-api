package tz.co.otapp.buscore.identityaccess.internal.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import tz.co.otapp.buscore.apicontracts.error.ApiException;
import tz.co.otapp.buscore.identityaccess.Principal;
import tz.co.otapp.buscore.identityaccess.PrincipalContext;
import tz.co.otapp.buscore.identityaccess.PrincipalType;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.ChangePasswordRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.PasswordResetIssued;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.RedeemPasswordResetRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.PasswordReset;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffCredential;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffIdentity;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.AccountStatus;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.AuthEventType;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.IdentityErrors;
import tz.co.otapp.buscore.identityaccess.internal.repository.PasswordResetRepository;
import tz.co.otapp.buscore.identityaccess.internal.repository.StaffCredentialRepository;
import tz.co.otapp.buscore.identityaccess.internal.repository.StaffIdentityRepository;
import tz.co.otapp.buscore.identityaccess.internal.security.ResetTokens;
import tz.co.otapp.buscore.identityaccess.internal.service.AuthAuditRecorder;
import tz.co.otapp.buscore.identityaccess.internal.service.CredentialService;
import tz.co.otapp.buscore.shared.logging.LogSanitizer;
import tz.co.otapp.buscore.shared.time.Times;

/**
 * The credential lifecycle.
 *
 * <p>Two rules carry over from sign-in unchanged, because this class verifies passwords too and a rule
 * honoured on one endpoint and not its neighbour is not honoured at all:
 *
 * <p><b>1 — The identifier is never an oracle.</b> Unknown identifier, wrong current password and
 * non-active account all leave by the same door with the same code, and an unknown identifier still pays
 * the hashing cost so the timing does not distinguish them either.
 *
 * <p><b>2 — Failure side effects must survive the rejection.</b> {@code noRollbackFor} keeps the failure
 * counter's increment when the refusal is thrown. Without it the counter never advances here, and the
 * change-password route becomes a guessing oracle with the lockout silently bypassed — sitting directly
 * beside the endpoint the lockout protects.
 */
@Service
@Transactional(noRollbackFor = ApiException.class)
@Slf4j
public class CredentialServiceImpl implements CredentialService {

    private final StaffIdentityRepository identities;
    private final StaffCredentialRepository credentials;
    private final PasswordResetRepository resets;
    private final PrincipalContext principalContext;
    private final AuthAuditRecorder auditRecorder;
    private final PasswordEncoder passwordEncoder;
    private final Duration resetTtl;

    public CredentialServiceImpl(StaffIdentityRepository identities, StaffCredentialRepository credentials,
            PasswordResetRepository resets, PrincipalContext principalContext,
            AuthAuditRecorder auditRecorder, PasswordEncoder passwordEncoder,
            @Value("${identity.password-reset.ttl:PT2H}") Duration resetTtl) {
        this.identities = identities;
        this.credentials = credentials;
        this.resets = resets;
        this.principalContext = principalContext;
        this.auditRecorder = auditRecorder;
        this.passwordEncoder = passwordEncoder;
        this.resetTtl = resetTtl;
    }

    // ─────────────────────── changing a password you already have ───────────────────────

    @Override
    public void changePassword(ChangePasswordRequest request) {
        Instant now = Times.now();
        String identifier = request.identifier().trim();

        Optional<StaffIdentity> found =
                identities.findByUsernameIgnoreCaseOrEmailIgnoreCase(identifier, identifier);

        if (found.isEmpty()) {
            // Hash anyway, against a value that cannot match. Returning early would make an unknown
            // identifier measurably faster than a wrong password, which is the same oracle the identical
            // error code exists to close.
            passwordEncoder.matches(request.currentPassword(), NO_SUCH_ACCOUNT_HASH);
            log.info("password change refused: no such identifier '{}'", LogSanitizer.clean(identifier, 128));
            auditRecorder.recordUnknownAccount(AuthEventType.LOGIN_FAILURE, identifier);
            throw invalidCredentials();
        }

        StaffIdentity identity = found.get();
        StaffCredential credential = credentials.findByStaffIdentity(identity)
                .orElseThrow(() -> {
                    // No credential means the account has never had a password. It cannot be changed, only
                    // set — and setting it needs a reset token, which is a different door.
                    log.info("password change refused: {} has no credential", identity.getUid());
                    return invalidCredentials();
                });

        // BEFORE the password is verified, exactly as at sign-in. A lock that still evaluates the presented
        // password stops nothing, and this route verifies one.
        if (credential.isLockedAt(now)) {
            auditRecorder.record(AuthEventType.LOGIN_BLOCKED_BY_LOCKOUT, PrincipalType.STAFF,
                    identity.getUid(), identifier);
            throw new ApiException(IdentityErrors.ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(request.currentPassword(), credential.getPasswordHash())) {
            credential.recordFailure(now);
            // Must commit even though the next lines throw — see the class javadoc.
            credentials.save(credential);
            auditRecorder.record(AuthEventType.LOGIN_FAILURE, PrincipalType.STAFF, identity.getUid(),
                    identifier);
            if (credential.isLockedAt(now)) {
                auditRecorder.record(AuthEventType.ACCOUNT_LOCKED, PrincipalType.STAFF, identity.getUid(),
                        identifier);
            }
            throw invalidCredentials();
        }

        // AFTER the password, so a suspended account stays indistinguishable from a wrong one. Note that a
        // PENDING account never reaches here: it has no credential, so it left by the branch above.
        if (!identity.canAuthenticate()) {
            auditRecorder.record(AuthEventType.LOGIN_FAILURE, PrincipalType.STAFF, identity.getUid(),
                    identifier);
            throw invalidCredentials();
        }

        // The one password rule that cannot be an annotation, because it needs the stored hash. It matters
        // most on a forced rotation, where "changing" the password to itself would satisfy the requirement
        // while defeating the reason for it.
        if (passwordEncoder.matches(request.newPassword(), credential.getPasswordHash())) {
            throw new ApiException(IdentityErrors.PASSWORD_UNCHANGED);
        }

        credential.changePassword(passwordEncoder.encode(request.newPassword()), now);
        credentials.save(credential);

        auditRecorder.record(AuthEventType.PASSWORD_CHANGED, PrincipalType.STAFF, identity.getUid(),
                identifier);
        log.info("password changed for {}", identity.getUid());
    }

    // ─────────────────────────────── issuing a reset ───────────────────────────────

    @Override
    public PasswordResetIssued issueReset(UUID staffUid) {
        Instant now = Times.now();
        StaffIdentity actor = actingAdministrator();
        StaffIdentity target = administrableTarget(staffUid, actor);

        if (target.isRoot()) {
            // ROOT's credential comes from the bootstrap configuration and nowhere else. A reset issued
            // through this surface would let anyone holding the permission take the break-glass identity,
            // which is the one account whose compromise has no recovery path.
            throw new ApiException(IdentityErrors.STAFF_NOT_MUTABLE,
                    "The root account's password is set by configuration, not here.");
        }

        // SUPERSEDE, don't accumulate. An administrator reissuing because "the link did not arrive" would
        // otherwise leave two live credentials for one account, the astray one working until it expired.
        // The partial unique index makes this mandatory rather than merely tidy — a second live row would
        // be refused by the database.
        List<PasswordReset> outstanding = resets.findAllByStaffIdentityAndConsumedAtIsNull(target);
        outstanding.forEach(reset -> reset.consume(now));
        resets.saveAll(outstanding);

        // FLUSHED, not merely saved. Hibernate is free to defer both statements to the end of the
        // transaction and to order the insert before the update — at which point the partial unique index
        // sees two live resets for one account and refuses the write. The index is the guarantee that
        // reissuing cannot leave two working credentials, so the ordering it demands is not optional.
        resets.flush();

        String token = ResetTokens.mint();
        Instant expiresAt = now.plus(resetTtl);
        resets.save(PasswordReset.of(target, ResetTokens.fingerprint(token), expiresAt, actor.getUid()));

        auditRecorder.record(AuthEventType.PASSWORD_RESET_ISSUED, PrincipalType.STAFF, target.getUid(),
                target.getUsername());
        // The token is absent from this line and from every other. It is a bearer credential, and a log is
        // a place secrets outlive the systems that made them.
        log.info("{} issued a password reset for {} (expires {}, superseded {})",
                actor.getUid(), target.getUid(), expiresAt, outstanding.size());

        return new PasswordResetIssued(token, expiresAt);
    }

    // ─────────────────────────────── redeeming one ───────────────────────────────

    @Override
    public void redeemReset(RedeemPasswordResetRequest request) {
        Instant now = Times.now();

        // Looked up by fingerprint, and by nothing else — the caller has proved nothing, so there is no
        // account to look up by. The token IS the claim about which account this is.
        PasswordReset reset = resets.findByTokenHash(ResetTokens.fingerprint(request.token()))
                .filter(candidate -> candidate.isRedeemableAt(now))
                .orElseThrow(() -> {
                    // Unknown, expired and already-spent are one answer to the caller and three rows in the
                    // trail. A run of these is somebody guessing tokens, and it is the only place the
                    // distinction survives.
                    auditRecorder.recordUnknownAccount(AuthEventType.PASSWORD_RESET_REJECTED, "reset-token");
                    log.info("password reset refused: token unknown, expired or already spent");
                    return new ApiException(IdentityErrors.RESET_TOKEN_INVALID);
                });

        StaffIdentity identity = reset.getStaffIdentity();
        String newHash = passwordEncoder.encode(request.newPassword());

        credentials.findByStaffIdentity(identity).ifPresentOrElse(
                credential -> {
                    credential.changePassword(newHash, now);
                    // A reset is also the remedy for a locked-out account, so it clears the counter. The
                    // holder proved possession of the token; making them wait out a lockout they may not
                    // have caused would leave the account unusable for no security gain.
                    credential.recordSuccess();
                    credentials.save(credential);
                },
                // The account's FIRST credential. This is the moment provisioning completes.
                () -> credentials.save(StaffCredential.of(identity, newHash, false)));

        // PENDING exists precisely to mean "provisioned, no password yet", so setting one ends it. Any
        // other status is left alone: a reset must never quietly un-suspend an account, which would turn
        // the power to reset a password into the power to restore access.
        if (identity.getStatus() == AccountStatus.PENDING) {
            identity.activate();
            identities.save(identity);
        }

        reset.consume(now);
        resets.save(reset);

        auditRecorder.record(AuthEventType.PASSWORD_RESET_REDEEMED, PrincipalType.STAFF, identity.getUid(),
                identity.getUsername());
        log.info("password set for {} via reset issued by {}", identity.getUid(), reset.getIssuedByUid());
    }

    // ───────────────────────────────── helpers ─────────────────────────────────

    private StaffIdentity actingAdministrator() {
        Principal principal = principalContext.require();
        return identities.findByUid(principal.uid())
                .orElseThrow(() -> new ApiException(IdentityErrors.STAFF_NOT_FOUND,
                        "The acting account no longer exists."));
    }

    /**
     * The target, if the caller's company contains it.
     *
     * <p>404 rather than 403 for another company's account, for the reason given on the staff
     * administration surface: 403 confirms the uid names a real account somewhere the caller cannot see.
     */
    private StaffIdentity administrableTarget(UUID staffUid, StaffIdentity actor) {
        StaffIdentity target = identities.findByUid(staffUid)
                .orElseThrow(() -> new ApiException(IdentityErrors.STAFF_NOT_FOUND));

        if (actor.getCompanyUid() != null && !actor.getCompanyUid().equals(target.getCompanyUid())) {
            throw new ApiException(IdentityErrors.STAFF_NOT_FOUND);
        }
        return target;
    }

    private ApiException invalidCredentials() {
        return new ApiException(IdentityErrors.INVALID_CREDENTIALS);
    }

    /**
     * A valid bcrypt hash of a value nobody will present, so verifying against a non-existent account costs
     * the same as verifying against a real one.
     *
     * <p>The {@code {bcrypt}} prefix is load-bearing: without it the delegating encoder throws rather than
     * returning false, which turns the timing equaliser into a 500 that distinguishes the case perfectly.
     */
    private static final String NO_SUCH_ACCOUNT_HASH =
            "{bcrypt}$2a$12$C6UzMDM.H6dfI/f/IKcEe.3vLtu0LWVjO/AtoKZI3fVJvVYQMB0Vy";
}
