package tz.co.otapp.buscore.identityaccess.internal.service;

import tz.co.otapp.buscore.identityaccess.internal.domain.dto.AgentLoginRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.LoginResponse;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.RefreshRequest;

/**
 * Agent sign-in — and, in this module, nothing else about an agent.
 *
 * <p>What an agent may <em>do</em> is the {@code agent} module's business: selling grants, for whom, under
 * what limits. That module is wave 6 to this module's wave 2, so the dependency cannot exist. This service
 * therefore answers exactly one question — <b>is this the agent they claim to be</b> — and issues a token
 * that asserts nothing more.
 *
 * <h2>The token carries an empty permission set, permanently</h2>
 *
 * <p>Not "empty for now". There is no future slice in which this module resolves an agent's authority,
 * because it cannot see the grants that confer it. Any code that treats an empty permission set as a
 * temporary state will be wrong forever.
 *
 * <p>This is exactly why the audience gate exists. It is tempting to think permissions already keep agents
 * off the staff surface — an agent holds none, so every gated route refuses them. True, and a
 * <b>coincidence</b>: a fact about an empty collection rather than a decision anyone made. The gate makes
 * it a decision.
 *
 * @see tz.co.otapp.buscore.identityaccess.internal.domain.entity.AgentCredential for why a PIN's lockout
 *      must be harsher than a password's, and what it still cannot defend against until slice 8
 */
public interface AgentAuthenticationService {

    /**
     * Exchange a phone number and PIN for a token.
     *
     * <p>Every refusal is the same refusal, in code and in timing — an unknown number, a malformed number,
     * a wrong PIN and a non-active account are indistinguishable. On this surface that matters more than it
     * does for staff: a staff username is guessed, whereas <b>a phone number is dialled</b>, and a login
     * that confirmed which numbers belong to agents would turn any contact list into a target list.
     *
     * @throws tz.co.otapp.buscore.apicontracts.error.ApiException {@code 401 AUTH.INVALID_CREDENTIALS} for
     *         every failure to prove identity; {@code 423 AUTH.ACCOUNT_LOCKED} when locked out;
     *         {@code 409 AUTH.PIN_CHANGE_REQUIRED} when the PIN is correct but must be replaced first —
     *         and no token is issued in that case
     */
    LoginResponse login(AgentLoginRequest request);

    /**
     * Renew an agent's session: rotate its refresh token and mint a fresh access token.
     *
     * <p>The renewed token asserts identity and nothing more, exactly as the sign-in token does — an agent's
     * authority is selling grants in another module, never anything this one could resolve. What refresh
     * re-checks is only that the login still exists and can authenticate; an agent whose login was withdrawn
     * mid-session stops renewing rather than carrying on to the session's expiry.
     *
     * @throws tz.co.otapp.buscore.apicontracts.error.ApiException {@code AUTH.REFRESH_TOKEN_INVALID} for a
     *         token that is unknown, for the staff surface, revoked, expired, or a replay of a spent one —
     *         and for a login since withdrawn, whose sessions are revoked in passing
     */
    LoginResponse refresh(RefreshRequest request);

    /**
     * End the session an agent's refresh token names. Idempotent, and touches only that one session.
     */
    void logout(RefreshRequest request);
}
