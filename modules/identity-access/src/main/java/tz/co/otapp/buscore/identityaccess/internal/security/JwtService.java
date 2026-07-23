package tz.co.otapp.buscore.identityaccess.internal.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import tz.co.otapp.buscore.identityaccess.Principal;
import tz.co.otapp.buscore.identityaccess.PrincipalType;
import tz.co.otapp.buscore.identityaccess.StaffTenancy;
import tz.co.otapp.buscore.shared.time.Times;

/**
 * Mints and verifies the bearer tokens that carry a {@link Principal} between requests.
 *
 * <h2>Self-issued and symmetric, on purpose</h2>
 *
 * <p>HS256 with a shared secret, because there is exactly one issuer and one verifier — this application.
 * Asymmetric keys earn their extra machinery when a token must be verified by something that must not be
 * able to mint one, and nothing here is in that position yet.
 *
 * <h2>The secret fails the application at startup, not at first login</h2>
 *
 * <p>An absent or short secret is a deployment mistake, and the useful moment to discover it is while
 * whoever deployed it is still watching. Failing closed at first use instead would mean the application
 * starts, reports healthy, and refuses every sign-in for a reason nothing in the health check mentions.
 *
 * <p>HS256 requires at least 256 bits of key material, so anything shorter is rejected outright rather
 * than padded — padding a weak secret produces a token that verifies while being trivially forgeable.
 *
 * <h2>What the token carries, and what it must not</h2>
 *
 * <p>Only the uid and the principal kind. Deliberately not the account's status, its tenancy, or later its
 * permissions: <b>anything baked into a token is a snapshot</b>, correct at issue and increasingly stale
 * for the whole of its lifetime. A suspended account would keep working until its token expired.
 *
 * <p>That is the argument for a short lifetime, and for resolving authority per request rather than
 * trusting what the token asserts about it.
 */
@Service
public class JwtService {

    /** HS256's minimum key size. A shorter secret is a forgeable one, so it is refused rather than padded. */
    private static final int MINIMUM_SECRET_BYTES = 32;

    /** Claim naming the kind of actor, so a future agent token cannot be mistaken for a staff one. */
    private static final String CLAIM_PRINCIPAL_TYPE = "typ";

    /** The staff tenancy. Absent for a non-staff principal, which is how the guard tells them apart. */
    private static final String CLAIM_TENANCY = "ten";

    /**
     * The flattened permission codes.
     *
     * <p>Carried in the token rather than resolved per request, which trades freshness for a database
     * round trip on every call. See {@link Principal} for what that costs and why the lifetime is short.
     */
    private static final String CLAIM_PERMISSIONS = "prm";

    /**
     * The operators a staff member serves.
     *
     * <p>Carried so the scope resolver needs no database read. Same snapshot caveat as the permissions:
     * an operator unlinked after sign-in stays reachable until the token expires.
     */
    private static final String CLAIM_OPERATORS = "ops";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration accessTokenTtl;
    private final String issuer;

    public JwtService(
            @Value("${identity.jwt.secret:}") String secret,
            @Value("${identity.jwt.access-token-ttl:PT15M}") Duration accessTokenTtl,
            @Value("${identity.jwt.issuer:bus-core}") String issuer) {

        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
            // Thrown during bean creation, so the application refuses to start. See the class javadoc.
            throw new IllegalStateException(
                    "identity.jwt.secret must be at least " + MINIMUM_SECRET_BYTES
                            + " bytes for HS256. Set IDENTITY_JWT_SECRET to a long random value; a short "
                            + "one produces tokens that verify and are trivially forgeable.");
        }

        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.accessTokenTtl = accessTokenTtl;
        this.issuer = issuer;
    }

    /** A freshly minted token and the moment it stops being accepted. */
    public record IssuedToken(String token, Instant expiresAt) {
    }

    /**
     * Mint a token for an authenticated actor.
     *
     * <p>Times come from the shared clock rather than {@code Instant.now()} so a test can move them — a
     * token lifetime that cannot be fast-forwarded cannot be tested at all without sleeping.
     */
    public IssuedToken issue(Principal principal) {
        Instant now = Times.now();
        Instant expiresAt = now.plus(accessTokenTtl);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(expiresAt)
                // The uid, never the numeric id: the subject of a token is a public handle by definition.
                .subject(principal.uid().toString())
                .claim(CLAIM_PRINCIPAL_TYPE, principal.type().name())
                // Written even when null/empty so a token's shape does not vary with its contents — a
                // parser that must cope with a missing claim eventually copes by assuming a default.
                .claim(CLAIM_TENANCY, principal.tenancy() == null ? null : principal.tenancy().name())
                .claim(CLAIM_PERMISSIONS, List.copyOf(principal.permissions()))
                .claim(CLAIM_OPERATORS, principal.operatorUids().stream().map(UUID::toString).toList())
                .build();

        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * Verify a token and recover the actor it names.
     *
     * <p>Returns empty rather than throwing for <em>any</em> unusable token — bad signature, expired,
     * malformed, unknown principal type. The caller is a filter that must treat "no credential" and "a
     * broken credential" identically; distinguishing them would tell a caller whether their forgery was
     * close.
     */
    public Optional<Principal> parse(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            PrincipalType type = PrincipalType.valueOf(jwt.getClaimAsString(CLAIM_PRINCIPAL_TYPE));
            String tenancyClaim = jwt.getClaimAsString(CLAIM_TENANCY);
            StaffTenancy tenancy = tenancyClaim == null ? null : StaffTenancy.valueOf(tenancyClaim);
            List<String> permissions = jwt.getClaimAsStringList(CLAIM_PERMISSIONS);
            List<String> operators = jwt.getClaimAsStringList(CLAIM_OPERATORS);
            return Optional.of(new Principal(
                    UUID.fromString(jwt.getSubject()),
                    type,
                    tenancy,
                    operators == null ? List.of() : operators.stream().map(UUID::fromString).toList(),
                    permissions == null ? Set.of() : Set.copyOf(permissions)));
        } catch (JwtException | IllegalArgumentException | NullPointerException unusableToken) {
            // IllegalArgumentException covers an unparseable uuid or an unrecognised principal type — the
            // shape of a token minted by an older or a forged issuer.
            return Optional.empty();
        }
    }
}
