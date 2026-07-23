package tz.co.otapp.buscore.identityaccess.internal.service;

import tz.co.otapp.buscore.identityaccess.internal.domain.dto.LoginRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.LoginResponse;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.StaffView;

/**
 * Staff sign-in, and reading back the signed-in account.
 *
 * <p>Kept narrow on purpose. A module-wide identity facade would mean that code needing only to read the
 * current account compiles against sign-in, lockout and — later — credential reset and MFA. Narrow
 * interfaces are what let a later slice change one of those without recompiling the callers of the others.
 */
public interface StaffAuthenticationService {

    /**
     * Verify credentials and issue a token.
     *
     * @throws tz.co.otapp.buscore.apicontracts.error.ApiException with
     *         {@code AUTH.INVALID_CREDENTIALS} for an unknown identifier, a wrong password, or any
     *         non-active account — <b>the same refusal for all three</b>, so the endpoint cannot be used
     *         to discover which accounts exist;
     *         {@code AUTH.ACCOUNT_LOCKED} when too many consecutive failures have locked the account;
     *         {@code AUTH.PASSWORD_CHANGE_REQUIRED} when the password is correct but must be rotated —
     *         and in that case <b>no token is issued</b>.
     */
    LoginResponse login(LoginRequest request);

    /**
     * The account behind the current token.
     *
     * @throws tz.co.otapp.buscore.apicontracts.error.ApiException {@code AUTH.NOT_AUTHENTICATED} when the
     *         request carries no valid token, or the token names an account that no longer exists — a
     *         token outliving its account is exactly the case a lookup must not quietly return null for.
     */
    StaffView currentStaff();
}
