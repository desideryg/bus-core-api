package tz.co.otapp.buscore.identityaccess;

import java.util.Optional;

/**
 * How a business module learns who is acting.
 *
 * <p>The one seam through which authority reaches the rest of the system. A module that needs to know the
 * caller depends on this interface — not on Spring Security, and not on anything under this module's
 * {@code internal} package. That is what keeps the identity module's internals out of every other module's
 * compile classpath while still letting all of them ask the question.
 *
 * <p>Implemented by reading the security context the authentication filter populated.
 */
public interface PrincipalContext {

    /**
     * The acting principal.
     *
     * @throws tz.co.otapp.buscore.apicontracts.error.ApiException {@code 401} when the request carries no
     *         authenticated actor. A filter-level guarantee turned into a service-level one, so a caller
     *         never proceeds on a null actor by forgetting to check.
     */
    Principal require();

    /** The acting principal, or empty when the request is unauthenticated. */
    Optional<Principal> current();
}
