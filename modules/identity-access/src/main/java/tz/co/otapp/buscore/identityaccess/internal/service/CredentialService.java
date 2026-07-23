package tz.co.otapp.buscore.identityaccess.internal.service;

import java.util.UUID;

import tz.co.otapp.buscore.identityaccess.internal.domain.dto.ChangePasswordRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.PasswordResetIssued;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.RedeemPasswordResetRequest;

/**
 * How an account gets a password, and how it gets a different one.
 *
 * <p>Before this slice both ends were dead: an account was provisioned {@code PENDING} with no credential
 * row and nobody could ever sign in to it, and an account flagged to rotate its password was refused a
 * token at sign-in with no route that could complete the rotation.
 *
 * <h2>Setting a password is not an administrative act</h2>
 *
 * <p>Neither {@link #changePassword} nor {@link #redeemReset} is authenticated, and neither is a hole. Each
 * carries its own proof: the current password in one case, a 256-bit one-time token in the other. What an
 * administrator can do is cause a <em>token</em> to exist — never set a password directly — which is what
 * keeps "who provisioned this account" and "who knows its password" different people.
 *
 * <p>The consequence is that {@link #issueReset} is effectively the power to take over an account, and it
 * carries a permission of its own that belongs to fewer people than the rest of staff administration.
 */
public interface CredentialService {

    /**
     * Change a password by presenting the current one.
     *
     * <p>Serves both a voluntary change and a forced rotation, since the holder has no token in either
     * case. <b>Counts failures and honours the lockout exactly as sign-in does</b> — otherwise it is a
     * guessing oracle sitting beside the endpoint the lockout protects, with the lockout bypassed.
     *
     * @throws tz.co.otapp.buscore.apicontracts.error.ApiException {@code 401} for any failure to prove the
     *         current password, indistinguishably from sign-in; {@code 423} when locked; {@code 400} when
     *         the new password is the current one
     */
    void changePassword(ChangePasswordRequest request);

    /**
     * Issue a one-time token for an account the caller administers.
     *
     * <p>Supersedes any outstanding reset, so reissuing because "the link did not arrive" cannot leave two
     * live credentials for one account. Creates the account's first credential path when it has none.
     *
     * @throws tz.co.otapp.buscore.apicontracts.error.ApiException {@code 404} when the account is not one
     *         the caller administers; {@code 409} for ROOT, whose credential is the bootstrap's alone
     */
    PasswordResetIssued issueReset(UUID staffUid);

    /**
     * Spend a token and set the password.
     *
     * <p>The token names the account; the request does not. A {@code PENDING} account becomes
     * {@code ACTIVE} here — this is the moment provisioning completes.
     *
     * @throws tz.co.otapp.buscore.apicontracts.error.ApiException {@code 400 AUTH.RESET_TOKEN_INVALID} when
     *         the token is unknown, expired or already spent — one answer for all three, because the caller
     *         has proved nothing and each distinct answer is a hint
     */
    void redeemReset(RedeemPasswordResetRequest request);
}
