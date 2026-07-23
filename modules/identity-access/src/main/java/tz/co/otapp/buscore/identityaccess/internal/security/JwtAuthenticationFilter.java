package tz.co.otapp.buscore.identityaccess.internal.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tz.co.otapp.buscore.identityaccess.Principal;

/**
 * Turns a bearer token into an authenticated security context.
 *
 * <h2>It never throws, and never rejects</h2>
 *
 * <p>A missing, malformed, expired or forged token all produce the same outcome: the context is left
 * empty and the chain continues. The refusal is then made once, by the authorisation rules, in one shape.
 *
 * <p>Two reasons. It keeps the answer identical whether a caller sent nothing or sent something broken —
 * a filter that reported "your token is expired" separately from "you sent no token" tells an attacker
 * whether a forgery was close. And it lets several authentication filters coexist without knowing about
 * each other, which is what a later slice needs when agents and machine clients arrive with their own
 * credential shapes.
 *
 * <h2>No authorities yet</h2>
 *
 * <p>The authentication is created with an empty authority list, because roles and permissions do not
 * exist until slice 2. What matters now is that the context holds our own {@link Principal} as its
 * principal, so business code reaches identity through {@code PrincipalContext} rather than through
 * Spring Security types.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        bearerTokenOf(request)
                .flatMap(jwtService::parse)
                .ifPresent(JwtAuthenticationFilter::authenticate);

        chain.doFilter(request, response);
    }

    private static java.util.Optional<String> bearerTokenOf(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return java.util.Optional.empty();
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(token);
    }

    private static void authenticate(Principal principal) {
        // Credentials are null: the token has already been verified, and holding it afterwards would only
        // create somewhere for it to be logged from.
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
