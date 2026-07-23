package tz.co.otapp.buscore.identityaccess.internal.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import tz.co.otapp.buscore.apicontracts.error.ApiException;
import tz.co.otapp.buscore.identityaccess.Principal;
import tz.co.otapp.buscore.identityaccess.PrincipalContext;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.IdentityErrors;

/**
 * Reads the acting principal out of the security context the authentication filter populated.
 *
 * <p>The single implementation of {@link PrincipalContext}, and the reason that interface exists: every
 * other module asks "who is acting" through the published port and never touches Spring Security or
 * anything under this module's {@code internal} package.
 *
 * <p>Reading a thread-local looks unfashionable, but the alternative is threading the principal through
 * every signature between the controller and the code that needs it — which the compiler would not check,
 * and which would eventually be short-circuited by someone reading it from a request body instead. That is
 * the failure this seam exists to make impossible.
 */
@Component
public class CurrentPrincipal implements PrincipalContext {

    @Override
    public Principal require() {
        return current().orElseThrow(() -> new ApiException(IdentityErrors.NOT_AUTHENTICATED));
    }

    @Override
    public Optional<Principal> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Principal principal)) {
            // The pattern match covers both the unauthenticated case and Spring's anonymous authentication,
            // whose principal is the string "anonymousUser" rather than anything of ours.
            return Optional.empty();
        }
        return Optional.of(principal);
    }
}
