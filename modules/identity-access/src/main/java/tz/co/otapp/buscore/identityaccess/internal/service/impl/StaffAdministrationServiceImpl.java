package tz.co.otapp.buscore.identityaccess.internal.service.impl;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tz.co.otapp.buscore.apicontracts.error.ApiException;
import tz.co.otapp.buscore.identityaccess.OperatorScope;
import tz.co.otapp.buscore.identityaccess.OperatorScopeResolver;
import tz.co.otapp.buscore.identityaccess.Principal;
import tz.co.otapp.buscore.identityaccess.PrincipalContext;
import tz.co.otapp.buscore.identityaccess.PrincipalType;
import tz.co.otapp.buscore.identityaccess.ScopeErrors;
import tz.co.otapp.buscore.identityaccess.StaffTenancy;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.CreateStaffRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.StaffView;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.SuspendStaffRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.UpdateStaffRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffIdentity;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffOperator;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.AccountStatus;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.AuthEventType;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.IdentityErrors;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.SessionRevocationReason;
import tz.co.otapp.buscore.identityaccess.internal.repository.StaffIdentityRepository;
import tz.co.otapp.buscore.identityaccess.internal.repository.StaffOperatorRepository;
import tz.co.otapp.buscore.identityaccess.internal.service.AuthAuditRecorder;
import tz.co.otapp.buscore.identityaccess.internal.service.AuthSessionService;
import tz.co.otapp.buscore.identityaccess.internal.service.StaffAdministrationService;

/**
 * Staff administration.
 *
 * <p>Every method starts by resolving the acting administrator and ends by recording what they did. The
 * middle differs; those two do not.
 *
 * <p>The caller's company is read from the database rather than from their token. It is one extra query on
 * a low-traffic surface, and it buys correctness that a claim cannot: a token is a snapshot that stays
 * valid for its whole lifetime, and administering accounts on the strength of a company an administrator
 * has since been moved out of is exactly the window this avoids.
 */
@RequiredArgsConstructor
@Service
@Transactional
@Slf4j
public class StaffAdministrationServiceImpl implements StaffAdministrationService {

    private final StaffIdentityRepository identities;
    private final StaffOperatorRepository memberships;
    private final PrincipalContext principalContext;
    private final OperatorScopeResolver scopes;
    private final AuthAuditRecorder auditRecorder;
    private final AuthSessionService authSessions;

    // ─────────────────────────────── provisioning ───────────────────────────────

    @Override
    public StaffView create(CreateStaffRequest request) {
        StaffIdentity actor = actingAdministrator();
        UUID companyUid = companyFor(request, actor);

        // Checked to produce a usable message, not as the guarantee — two concurrent creates both see false
        // here. The functional unique indexes on lower(username) and lower(email) are what admit one.
        if (identities.existsByUsernameIgnoreCase(request.username())
                || identities.existsByEmailIgnoreCase(request.email())) {
            throw new ApiException(IdentityErrors.STAFF_ALREADY_EXISTS);
        }

        // PENDING, and no credential row: the account exists but cannot sign in until a password is set
        // through the credential surface. Provisioning an account and knowing its password are separate
        // powers, and this is what keeps them separate.
        StaffIdentity created = identities.save(companyUid == null
                ? StaffIdentity.ofPlatform(request.username(), request.email(), request.displayName(),
                        request.tenancy(), AccountStatus.PENDING)
                : StaffIdentity.of(request.username(), request.email(), request.displayName(),
                        request.tenancy(), AccountStatus.PENDING, companyUid));

        auditRecorder.record(AuthEventType.STAFF_CREATED, PrincipalType.STAFF, created.getUid(),
                request.username());
        log.info("{} created staff account {} ({})", actor.getUid(), created.getUid(), request.tenancy());
        return StaffView.of(created);
    }

    /**
     * Which company the new account belongs to, and whether the caller may create it at all.
     *
     * <p>Returns null for an account that belongs to no company. The two escalation rules live together
     * here because they are one decision: what a caller may create is entirely determined by what they are.
     */
    private UUID companyFor(CreateStaffRequest request, StaffIdentity actor) {
        if (request.tenancy() == StaffTenancy.ROOT) {
            // Nobody creates ROOT through an API — not even ROOT. There is exactly one, it is created by the
            // bootstrap, and the partial unique index would refuse a second anyway. Refusing here means the
            // caller gets an explanation rather than a constraint violation.
            throw new ApiException(IdentityErrors.TENANCY_NOT_PERMITTED,
                    "The root account cannot be created through this surface.");
        }

        if (actor.getCompanyUid() != null) {
            // Operator staff. They may create only their own kind, and only in their own company — the body
            // does not get a say, because a body that chose the company would be an authority field.
            if (request.tenancy() != StaffTenancy.OPERATOR) {
                throw new ApiException(IdentityErrors.TENANCY_NOT_PERMITTED,
                        "You can only create operator accounts.");
            }
            return actor.getCompanyUid();
        }

        if (actor.getTenancy() == StaffTenancy.PARTNER) {
            // A partner belongs to no company in this hierarchy and has no tenancy to create into, so there
            // is no account they could legitimately mint. Fail closed rather than fall through to the
            // platform branch below, which would hand them the power to create administrators.
            throw new ApiException(IdentityErrors.TENANCY_NOT_PERMITTED,
                    "Partner accounts cannot provision staff.");
        }

        // Platform staff. They belong to no company, so an operator account's company is genuinely
        // undetermined and they must name it — the same shape as OperatorScope#requireTarget, and backwards
        // for the same reason it is there: the widest caller is the one who has to be specific.
        if (request.tenancy() == StaffTenancy.OPERATOR) {
            if (request.companyUid() == null) {
                throw new ApiException(IdentityErrors.COMPANY_REQUIRED);
            }
            return request.companyUid();
        }
        return null;
    }

    // ─────────────────────────────── editing ───────────────────────────────

    @Override
    public StaffView update(UUID staffUid, UpdateStaffRequest request) {
        StaffIdentity actor = actingAdministrator();
        StaffIdentity target = requireAdministrable(staffUid);

        // Only the display name moves. The username and email are fixed after provisioning — see
        // UpdateStaffRequest — so there is one field to set and no partial-update shape to reason about.
        target.changeDisplayName(request.displayName().trim());
        identities.save(target);

        auditRecorder.record(AuthEventType.STAFF_UPDATED, PrincipalType.STAFF, staffUid, target.getUsername());
        log.info("{} updated staff account {}", actor.getUid(), staffUid);
        return StaffView.of(target);
    }

    // ───────────────────────────────── reading ─────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<StaffView> list() {
        UUID company = actingAdministrator().getCompanyUid();

        List<StaffIdentity> visible = company == null
                ? identities.findAllByOrderByUsername()
                : identities.findAllByCompanyUidOrderByUsername(company);

        return visible.stream().map(StaffView::of).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StaffView get(UUID staffUid) {
        return StaffView.of(requireAdministrable(staffUid));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> operatorsOf(UUID staffUid) {
        return memberships.findOperatorUids(requireAdministrable(staffUid));
    }

    // ─────────────────────────────── withdrawal ───────────────────────────────

    @Override
    public void suspend(UUID staffUid, SuspendStaffRequest request) {
        StaffIdentity actor = actingAdministrator();
        StaffIdentity target = requireAdministrable(staffUid);

        if (target.isRoot()) {
            // The break-glass identity is what rescues the system when administration itself is broken.
            // Suspending it is the one action that could make a recoverable incident unrecoverable.
            throw new ApiException(IdentityErrors.STAFF_NOT_MUTABLE,
                    "The root account cannot be suspended.");
        }
        if (target.getUid().equals(actor.getUid())) {
            // Not paternalism — an administrator who suspends themselves cannot undo it, because undoing it
            // needs a session they no longer have.
            throw new ApiException(IdentityErrors.STAFF_NOT_MUTABLE,
                    "You cannot withdraw your own access.");
        }

        target.withdraw(request.status());

        // WITHDRAWING ACCESS ENDS THE ACCOUNT'S SESSIONS — this is what makes the withdrawal take effect now
        // rather than whenever each live token happened to expire. An access token already issued still works
        // until its short TTL runs out (it is stateless and unread), but no session can be refreshed past
        // this, so the account is out within minutes instead of up to a refresh lifetime. Without it,
        // suspending a compromised account would leave every open session renewing itself indefinitely.
        authSessions.revokeAllFor(target.getUid(), PrincipalType.STAFF,
                SessionRevocationReason.ACCOUNT_WITHDRAWN);

        // The reason is recorded rather than stored on the account: an account has one current status but a
        // history of withdrawals, and a column would keep only the most recent explanation.
        auditRecorder.record(AuthEventType.STAFF_SUSPENDED, PrincipalType.STAFF, staffUid,
                request.status().getValue() + (request.reason() == null ? "" : ": " + request.reason()));
        log.info("{} withdrew access for {} ({})", actor.getUid(), staffUid, request.status());
    }

    @Override
    public void restore(UUID staffUid) {
        StaffIdentity actor = actingAdministrator();
        StaffIdentity target = requireAdministrable(staffUid);

        if (target.isRoot()) {
            // Unreachable while ROOT cannot be suspended, and kept because the pair must move together: a
            // later change that permits suspending ROOT must not silently also permit restoring it.
            throw new ApiException(IdentityErrors.STAFF_NOT_MUTABLE,
                    "The root account cannot be restored.");
        }

        target.restore();
        auditRecorder.record(AuthEventType.STAFF_RESTORED, PrincipalType.STAFF, staffUid,
                target.getUsername());
        log.info("{} restored access for {}", actor.getUid(), staffUid);
    }

    // ─────────────────────────────── memberships ───────────────────────────────

    @Override
    public void linkOperator(UUID staffUid, UUID operatorUid) {
        StaffIdentity actor = actingAdministrator();
        StaffIdentity target = requireAdministrable(staffUid);

        if (target.getTenancy() != StaffTenancy.OPERATOR) {
            // Platform staff already reach every operator; a membership would be either redundant or a
            // contradiction. The composite foreign key refuses it too — their company is null and a
            // membership's is not — so this exists to say why rather than to enforce it.
            throw new ApiException(IdentityErrors.TENANCY_NOT_PERMITTED,
                    "Only operator accounts hold operator memberships.");
        }

        // THE ESCALATION GUARD. An administrator may only hand out reach they already have, so an operator
        // administrator cannot attach an operator they do not themselves serve. Platform staff permit
        // everything, which is what allows them to set up an operator's first administrator.
        OperatorScope scope = scopes.require();
        if (!scope.permits(operatorUid)) {
            throw new ApiException(ScopeErrors.NOT_AUTHORISED,
                    "That operator is outside your scope.");
        }

        if (memberships.existsByStaffIdentityAndOperatorUid(target, operatorUid)) {
            // Idempotent. A retried request must not be an error the caller can do nothing about.
            log.debug("{} already serves {}", staffUid, operatorUid);
            return;
        }

        // The company is taken from the target, never supplied — and the composite foreign key would refuse
        // the row if the two disagreed. See V4__create_staff_operators.sql.
        memberships.save(StaffOperator.of(target, operatorUid));
        auditRecorder.record(AuthEventType.OPERATOR_LINKED, PrincipalType.STAFF, staffUid,
                operatorUid.toString());
        log.info("{} linked {} to operator {}", actor.getUid(), staffUid, operatorUid);
    }

    @Override
    public void unlinkOperator(UUID staffUid, UUID operatorUid) {
        StaffIdentity actor = actingAdministrator();
        StaffIdentity target = requireAdministrable(staffUid);

        // No scope check on the operator, unlike linking. Unlinking only ever narrows access, and refusing
        // it would mean an administrator who can see that a membership exists cannot remove it — precisely
        // the wrong way round during an incident.
        memberships.deleteByStaffIdentityAndOperatorUid(target, operatorUid);
        auditRecorder.record(AuthEventType.OPERATOR_UNLINKED, PrincipalType.STAFF, staffUid,
                operatorUid.toString());
        log.info("{} unlinked {} from operator {}", actor.getUid(), staffUid, operatorUid);
    }

    // ───────────────────────────────── helpers ─────────────────────────────────

    /**
     * The administrator making the request, as a row rather than a token claim.
     *
     * <p>Non-staff cannot reach here — every route is behind the admin audience gate and a permission — so
     * the check is a belt-and-braces refusal rather than a live branch.
     */
    private StaffIdentity actingAdministrator() {
        Principal principal = principalContext.require();
        if (principal.type() != PrincipalType.STAFF) {
            throw new ApiException(ScopeErrors.NOT_AUTHORISED);
        }
        return identities.findByUid(principal.uid())
                .orElseThrow(() -> new ApiException(IdentityErrors.STAFF_NOT_FOUND,
                        "The acting account no longer exists."));
    }

    /**
     * The target account, if the caller's company contains it.
     *
     * <p><b>404 rather than 403 for another company's account</b>, and that is the one place in this class
     * where a refusal is deliberately vague. Everywhere else the caller has already been granted
     * administrative permission and telling them exactly what was wrong discloses nothing. Here it would:
     * 403 confirms that a uid names a real account in a company they cannot see, and repeated probing turns
     * the difference into a directory of another business's staff.
     */
    private StaffIdentity requireAdministrable(UUID staffUid) {
        StaffIdentity target = identities.findByUid(staffUid)
                .orElseThrow(() -> new ApiException(IdentityErrors.STAFF_NOT_FOUND));

        UUID company = actingAdministrator().getCompanyUid();
        if (company != null && !company.equals(target.getCompanyUid())) {
            throw new ApiException(IdentityErrors.STAFF_NOT_FOUND);
        }
        return target;
    }
}
