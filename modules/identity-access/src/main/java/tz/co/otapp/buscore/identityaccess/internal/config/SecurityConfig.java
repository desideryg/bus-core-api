package tz.co.otapp.buscore.identityaccess.internal.config;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// Spring Boot 4 ships JACKSON 3, whose root package is tools.jackson — not com.fasterxml.jackson.
// Both can appear on a classpath at once (some libraries still carry Jackson 2), so importing the
// familiar one compiles nothing here and, where it does compile, silently uses a second mapper
// configured differently from the one Spring is using for every other response.
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;
import tz.co.otapp.buscore.apicontracts.error.ErrorCode;
import tz.co.otapp.buscore.apicontracts.response.ApiResponse;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.IdentityErrors;
import tz.co.otapp.buscore.identityaccess.internal.security.AudienceFilter;
import tz.co.otapp.buscore.identityaccess.internal.security.JwtAuthenticationFilter;

/**
 * The application's filter chain, owned by the module that knows what a principal is.
 *
 * <h2>Stateless</h2>
 *
 * <p>No HTTP session, no CSRF token. Authority travels in a bearer token that is verified on every
 * request, so there is no server-side session for a forged form post to ride — which is what CSRF
 * protection defends, and defending it here would only break every non-browser client.
 *
 * <h2>The public list is short, and everything else is closed</h2>
 *
 * <p>{@code anyRequest().authenticated()} is the default, so a route added tomorrow is protected by
 * omission rather than by someone remembering. Only doors that <em>present their own credential</em> are
 * public, and each is named individually — a wildcard under {@code /auth/**} would silently expose the
 * password-change and sign-out endpoints that later slices add beside sign-in.
 *
 * <h2>Refusals are rendered in our envelope</h2>
 *
 * <p>Spring Security's defaults return an empty body with a {@code WWW-Authenticate} header. That would be
 * the one response shape in the whole application that is not the envelope — so a client would need a
 * second parser for exactly the case where things are already going wrong.
 */
/*
 * DELIBERATELY NOT PROFILE-GATED, unlike this module's persistence configuration.
 *
 * Nothing the chain needs touches a database: token verification, the principal seam and the password
 * encoder are all pure. Gating it alongside the repositories was tried and produced exactly the defect
 * documented in the reference implementation's gateway role — the chain disappeared, Boot's default
 * appeared in its place, and every route sat behind a generated password nobody had chosen.
 *
 * The rule that came out of that: A CONFIGURATION MAY ONLY BE SWITCHED OFF IF NOTHING WOULD QUIETLY TAKE
 * ITS PLACE. Security has a framework default; persistence does not.
 */
@Configuration
@ComponentScan(basePackages = "tz.co.otapp.buscore.identityaccess.internal.security")
/*
 * METHOD SECURITY IS ENABLED HERE, in the same class as the chain and gated on nothing.
 *
 * If it were absent, every @PreAuthorize in the reactor would be INERT — present, reviewed, and doing
 * nothing. No test would fail, because a test that expects a 200 still gets one. The failure is entirely
 * silent, which is why this annotation sits beside the chain rather than in a configuration of its own
 * that could be conditionally excluded.
 */
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Doors that carry their own credential.
     *
     * <p>Ordering matters in the chain below: these must be permitted <b>before</b> the catch-all, or
     * sign-in would require a token in order to obtain one and nobody could ever authenticate.
     */
    private static final String[] PUBLIC_PATHS = {
            "/admin/v1/auth/login",

            // Changing a password cannot require a token, because the account most likely to need it has
            // just been refused one: a forced rotation returns 409 and no token, deliberately, so that a
            // rotation-pending account is not quietly fully usable. Requiring a token here would leave the
            // holder with a password they must change and no way to change it.
            //
            // Not unauthenticated in substance — the current password is the authorisation, and the route
            // counts failures and honours the lockout exactly as sign-in does.
            "/admin/v1/auth/password",

            // Redeeming a reset token is how an account that has never had a password gets its first one.
            // There is no credential to authenticate with by definition; the 256-bit token is the proof.
            "/admin/v1/auth/password/redeem",

            // The agent door. Under an audience-scoped prefix, which is fine: AudienceFilter has no opinion
            // on an unauthenticated request, so the gate cannot refuse the very request that would produce
            // the token it checks for.
            "/agent/v1/auth/login",

            // The walking skeleton and the orchestrator probe. Health is public because a probe cannot
            // hold a credential; the detail it exposes is already restricted by the actuator config.
            "/ping",
            "/actuator/health",
            "/actuator/health/**"
    };

    /**
     * BCrypt at strength 12, wrapped so the algorithm is recorded in the hash itself.
     *
     * <p>The {@code {bcrypt}} prefix is what makes a future migration possible without a flag day: a
     * verifier reads the prefix and uses the right algorithm, so old and new hashes can coexist while
     * accounts re-hash on next sign-in. A bare encoder stores no such marker, and changing algorithm then
     * means invalidating every password at once.
     *
     * <p>Strength 12 rather than the default 10 — roughly 250ms per verification, which is negligible for
     * staff sign-in volume and multiplies an offline attacker's cost fourfold.
     *
     * <p><b>Agents reuse this bean, through {@code PinEncoder}, and the reason is worth stating</b> because
     * an earlier note here predicted the opposite — that a PIN would need its own, higher cost.
     *
     * <p>It would not have helped. A work factor decides how long an offline attacker takes per candidate,
     * and a six-digit PIN has only a million candidates: quadrupling the cost quadruples a weekend. The
     * defence that actually changes the outcome is a key the database does not contain, so {@code
     * PinEncoder} HMACs the PIN with a server-side pepper before handing it here. That leaves this bean
     * doing what it is good at — the adaptive hash — and puts the part a PIN genuinely needs somewhere a
     * stolen table does not reach.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        String defaultAlgorithm = "bcrypt";
        return new DelegatingPasswordEncoder(defaultAlgorithm,
                Map.of(defaultAlgorithm, new BCryptPasswordEncoder(12)));
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
            AudienceFilter audienceFilter, ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                // Before the username/password filter, which is where Spring expects an authentication
                // mechanism to sit. The chain has no form login, but the position is the conventional one
                // and keeps this filter ahead of everything that assumes a populated context.
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // AFTER the JWT filter, because it needs a populated principal to have an opinion, and
                // BEFORE authorisation, so a caller on the wrong surface is told that rather than being
                // told they lack a permission no grant could ever give them.
                .addFilterAfter(audienceFilter, JwtAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) ->
                                writeEnvelope(response, objectMapper, IdentityErrors.NOT_AUTHENTICATED))
                        .accessDeniedHandler((request, response, exception) ->
                                writeEnvelope(response, objectMapper, tz.co.otapp.buscore.apicontracts.error
                                        .CommonErrors.FORBIDDEN)))
                .build();
    }

    /**
     * Render a refusal in the standard envelope.
     *
     * <p>Written directly to the response rather than thrown, because these run in the filter chain —
     * outside the dispatcher, where the exception advice that would otherwise shape it never sees them.
     * That gap is precisely why security refusals are the responses most likely to end up in a different
     * shape from every other one.
     */
    private static void writeEnvelope(HttpServletResponse response, ObjectMapper objectMapper, ErrorCode code)
            throws IOException {
        response.setStatus(code.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiResponse<Void> body = ApiResponse.failure(code, code.defaultMessage(), List.of());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
