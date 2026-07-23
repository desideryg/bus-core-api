package tz.co.otapp.buscore.identityaccess.internal.service;

import java.util.List;
import java.util.UUID;

import tz.co.otapp.buscore.identityaccess.internal.domain.dto.CreateStaffRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.StaffView;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.SuspendStaffRequest;

/**
 * Provisioning and administering staff login identities.
 *
 * <h2>Two different boundaries, and confusing them is the bug</h2>
 *
 * <p>This module now has two notions of "what a caller may reach", and they are not the same:
 *
 * <ul>
 *   <li><b>Company</b> bounds <em>administration</em>. An operator administrator manages the accounts of
 *       their own company and no other. Used by every method here.</li>
 *   <li><b>Operator memberships</b> ({@link tz.co.otapp.buscore.identityaccess.OperatorScope}) bound
 *       <em>data</em> — whose vehicles, trips and bookings a staff member sees. Used by every other module,
 *       and here only when deciding which operators a caller may attach.</li>
 * </ul>
 *
 * <p>Scoping administration by memberships instead would strand a newly created account: it has none, so it
 * would be invisible to the very administrator who just created it.
 *
 * <h2>Permissions say "may"; this class says "may, to whom"</h2>
 *
 * <p>{@code @PreAuthorize} can only ask whether a caller holds a code. It cannot express "may create an
 * account, but not one more powerful than their own", because that depends on the target as well as the
 * actor. Those limits therefore live here, and they are refusals rather than grants: holding
 * {@code STAFF.CREATE} never implies the right to mint a platform administrator.
 */
public interface StaffAdministrationService {

    /**
     * Provision an account.
     *
     * <p>Created {@code PENDING} with no credential, so it cannot sign in until a password is set. Whoever
     * provisions accounts therefore never knows the credential of the accounts they provision.
     *
     * @throws tz.co.otapp.buscore.apicontracts.error.ApiException {@code 409} when the username or email is
     *         taken; {@code 403} when the caller may not create an account of that tenancy; {@code 400} when
     *         an operator account is requested without a company
     */
    StaffView create(CreateStaffRequest request);

    /** The accounts the caller administers — every account for platform staff, their company's otherwise. */
    List<StaffView> list();

    /** One account, if it is inside the caller's company. */
    StaffView get(UUID staffUid);

    /**
     * Withdraw an account's access.
     *
     * <p>Refuses ROOT and refuses the caller's own account: the first would lock the break-glass identity
     * out of the system it exists to rescue, and the second lets an administrator strand themselves mid-task
     * with no way back except another administrator.
     */
    void suspend(UUID staffUid, SuspendStaffRequest request);

    /** Return a withdrawn account to use. */
    void restore(UUID staffUid);

    /** The operators an account serves. */
    List<UUID> operatorsOf(UUID staffUid);

    /**
     * Attach an operator, widening whose rows the account reaches.
     *
     * <p>The operator must be one the <em>caller</em> already reaches, so linking can never hand out access
     * the linker does not have. Idempotent: linking twice is a no-op, not an error.
     */
    void linkOperator(UUID staffUid, UUID operatorUid);

    /** Detach one. Idempotent, so "already unlinked" is success — during an incident the question is now. */
    void unlinkOperator(UUID staffUid, UUID operatorUid);
}
