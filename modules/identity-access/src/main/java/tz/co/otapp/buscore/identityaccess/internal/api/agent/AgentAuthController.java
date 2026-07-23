package tz.co.otapp.buscore.identityaccess.internal.api.agent;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tz.co.otapp.buscore.apicontracts.response.ApiResponse;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.AgentLoginRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.LoginResponse;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.RefreshRequest;
import tz.co.otapp.buscore.identityaccess.internal.service.AgentAuthenticationService;

/**
 * The agent sign-in door — the first thing to serve {@code /agent/v1/**}, and therefore the first request
 * the audience gate has ever had an opinion about.
 *
 * <p><b>No permission expression appears here, and none ever may.</b> An agent's permission set is empty by
 * construction, so any {@code @PreAuthorize} on this surface would deny every agent alive. Authorisation
 * here is the audience gate plus the selling grants held in the {@code agent} module — see this package's
 * {@code package-info}.
 *
 * <p>This surface is <b>sign-in and its session lifecycle</b> — login, refresh, logout — and nothing about
 * an agent beyond it. <b>Provisioning an agent, setting a first PIN and recovering a forgotten one are still
 * not here</b>, which means agent rows are created by migration or by hand until the slice that owns
 * provisioning lands. That is a real gap and it is stated rather than implied.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/v1/auth")
public class AgentAuthController {

    private final AgentAuthenticationService agentAuthentication;

    /**
     * Exchange a phone number and PIN for a token.
     *
     * <p>Public, because it presents its own credential and requiring a token to obtain one would be
     * circular. It is also the reason {@code /agent/v1/auth/login} has to be named in
     * {@code PUBLIC_PATHS}: everything under the prefix is {@code authenticated()} by default, so a route
     * added tomorrow is protected by omission rather than by somebody remembering.
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody AgentLoginRequest request) {
        return ApiResponse.ok(agentAuthentication.login(request), "Signed in.");
    }

    /**
     * Exchange a refresh token for a fresh access token, rotating the refresh token in the process.
     *
     * <p>Public for the same reason login is, and named in {@code PUBLIC_PATHS} for the same reason: the
     * access token it renews has most likely expired, so requiring one would make the mechanism unusable
     * exactly when the agent needs it. The refresh token's own session records that it belongs to the agent
     * surface, so a staff token presented here is refused as an unknown one.
     */
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(agentAuthentication.refresh(request), "Session extended.");
    }

    /**
     * End a session by presenting its refresh token. Public and idempotent, exactly as the staff door is.
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody RefreshRequest request) {
        agentAuthentication.logout(request);
        return ApiResponse.done("Signed out.");
    }
}
