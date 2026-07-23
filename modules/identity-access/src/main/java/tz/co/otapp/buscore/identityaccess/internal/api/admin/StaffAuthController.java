package tz.co.otapp.buscore.identityaccess.internal.api.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tz.co.otapp.buscore.apicontracts.response.ApiResponse;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.ChangePasswordRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.LoginRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.LoginResponse;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.RedeemPasswordResetRequest;
import tz.co.otapp.buscore.identityaccess.internal.domain.dto.StaffView;
import tz.co.otapp.buscore.identityaccess.internal.service.CredentialService;
import tz.co.otapp.buscore.identityaccess.internal.service.StaffAuthenticationService;

/**
 * The staff sign-in doors.
 *
 * <p>Mappings are <b>relative</b>: the {@code /api} prefix comes once from the servlet context path, so
 * these routes serve at {@code /api/admin/v1/auth/…}. Security matchers see the same post-strip path, which
 * is what lets a matcher and a mapping be compared by eye.
 *
 * <h2>Handlers are public, and that is not a style choice</h2>
 *
 * <p>Spring's method-security proxy does not advise a package-private method. A gated handler that is
 * package-private is a silent no-op — the annotation is present, the rule reports green, and the door is
 * open. Nothing here is gated yet, but the habit is established now rather than discovered later.
 *
 * <h2>No delegate yet</h2>
 *
 * <p>The convention is that a controller is a thin shim over a delegate which resolves the acting principal
 * and adapts before calling a service. There is nothing to adapt in this slice — the service resolves the
 * principal itself — so a delegate would be a file that forwards two calls and nothing else. It arrives
 * with the first endpoint that has to resolve an operator scope or reshape a result.
 */
@RestController
@RequestMapping("/admin/v1/auth")
public class StaffAuthController {

    private final StaffAuthenticationService staffAuthentication;
    private final CredentialService credentials;

    public StaffAuthController(StaffAuthenticationService staffAuthentication,
            CredentialService credentials) {
        this.staffAuthentication = staffAuthentication;
        this.credentials = credentials;
    }

    /**
     * Sign in with a username or email and a password.
     *
     * <p>Public: it presents its own credential, and requiring a token to obtain one would be circular.
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(staffAuthentication.login(request), "Signed in.");
    }

    /**
     * The account behind the presented token.
     *
     * <p>The route that proves authentication actually works end to end: it is reachable only with a token
     * this application minted, and it reports who that token names rather than echoing anything the caller
     * sent.
     */
    @GetMapping("/me")
    public ApiResponse<StaffView> me() {
        return ApiResponse.ok(staffAuthentication.currentStaff());
    }

    /**
     * Change a password by presenting the current one.
     *
     * <p><b>Public, and that is deliberate.</b> An account required to rotate its password is refused a
     * token at sign-in — issuing one so this endpoint could be called would make the account fully usable
     * while nominally requiring a rotation — so the holder has no token to present here. The current
     * password is the authorisation, which works identically whether the rotation was forced or chosen.
     *
     * <p>It verifies a password, so it counts failures and honours the lockout exactly as sign-in does.
     */
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        credentials.changePassword(request);
        return ApiResponse.done("Password changed. Sign in with the new one.");
    }

    /**
     * Set a password using a one-time token.
     *
     * <p>Public because the token is the whole of the authorisation — it is how an account that has never
     * had a password gets its first one, and how a locked-out holder recovers.
     */
    @PostMapping("/password/redeem")
    public ApiResponse<Void> redeemReset(@Valid @RequestBody RedeemPasswordResetRequest request) {
        credentials.redeemReset(request);
        return ApiResponse.done("Password set. You can sign in now.");
    }
}
