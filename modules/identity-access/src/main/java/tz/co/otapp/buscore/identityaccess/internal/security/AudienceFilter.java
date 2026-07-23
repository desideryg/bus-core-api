package tz.co.otapp.buscore.identityaccess.internal.security;

import java.io.IOException;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import tz.co.otapp.buscore.apicontracts.response.ApiResponse;
import tz.co.otapp.buscore.identityaccess.Principal;
import tz.co.otapp.buscore.identityaccess.PrincipalContext;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.IdentityErrors;

/**
 * Refuses a caller of the wrong kind before any permission is consulted.
 *
 * <p>Runs after authentication and before authorisation, so it sees a populated principal and can answer
 * with a code of its own rather than the generic denial the authorisation layer produces.
 *
 * <h2>Three refusals, three codes, three remedies</h2>
 *
 * <table border="1">
 *   <caption>Why this is not merged into the permission check</caption>
 *   <tr><th>Refusal</th><th>Code</th><th>What the caller does about it</th></tr>
 *   <tr><td>Wrong kind of caller</td><td>{@code AUTH.AUDIENCE_MISMATCH}</td>
 *       <td>Use the surface meant for them — nothing can be granted to fix it</td></tr>
 *   <tr><td>Lacks the permission</td><td>{@code COMMON.FORBIDDEN}</td>
 *       <td>Ask for the role that carries it</td></tr>
 *   <tr><td>Another operator's rows</td><td>{@code SCOPE.*} (slice 5)</td>
 *       <td>Nothing — the row is not theirs</td></tr>
 * </table>
 *
 * <p>Collapsing them into one code would leave a caller and a support desk unable to tell an unfixable
 * situation from a one-line grant.
 *
 * <h2>Unauthenticated requests pass straight through</h2>
 *
 * <p>An absent principal is not an audience mismatch — it is a missing credential, and the chain already
 * answers that with 401. Refusing here first would report the wrong problem, and would tell an
 * unauthenticated caller which prefixes exist.
 *
 * <h2>Why it writes the response itself</h2>
 *
 * <p>Filters run outside the dispatcher, so the exception advice that shapes every other response never
 * sees what happens here. Writing the envelope directly is what keeps a security refusal from being the
 * one response in the application with a different shape.
 */
@Component
@Slf4j
public class AudienceFilter extends OncePerRequestFilter {

    private final PrincipalContext principalContext;
    private final ObjectMapper objectMapper;

    public AudienceFilter(PrincipalContext principalContext, ObjectMapper objectMapper) {
        this.principalContext = principalContext;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Audience audience = Audience.of(pathWithinApplication(request));
        Principal principal = principalContext.current().orElse(null);

        // Not an audience-scoped path, or nobody is authenticated yet. Either way this filter has no
        // opinion — see the class javadoc.
        if (audience == null || principal == null) {
            chain.doFilter(request, response);
            return;
        }

        if (!audience.permits(principal.type())) {
            log.info("audience mismatch: {} principal at {}", principal.type(), audience);
            writeRefusal(response);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * The request path with the context path removed.
     *
     * <p><b>Not {@code getServletPath()}</b>, which is the obvious choice and is wrong here: it is
     * populated by a real servlet container but empty under MockMvc, so a gate built on it passes every
     * test while working in production — or, far worse, the reverse. Deriving it from the request URI
     * behaves identically in both.
     *
     * <p>The context path is stripped because the audience prefixes are declared relative, exactly as
     * controller mappings are. Over real HTTP this same request is {@code /api/agent/v1/…}.
     */
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath == null || contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
    }

    private void writeRefusal(HttpServletResponse response) throws IOException {
        response.setStatus(IdentityErrors.AUDIENCE_MISMATCH.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiResponse<Void> body = ApiResponse.failure(
                IdentityErrors.AUDIENCE_MISMATCH, IdentityErrors.AUDIENCE_MISMATCH.defaultMessage(), List.of());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
