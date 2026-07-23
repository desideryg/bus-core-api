package tz.co.otapp.buscore.identityaccess.internal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import tz.co.otapp.buscore.identityaccess.StaffTenancy;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffCredential;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.StaffIdentity;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.AccountStatus;
import tz.co.otapp.buscore.identityaccess.internal.repository.StaffCredentialRepository;
import tz.co.otapp.buscore.identityaccess.internal.repository.StaffIdentityRepository;

/**
 * Creates the break-glass ROOT account on an otherwise empty database.
 *
 * <p>Something has to exist before anything can be administered — the first role grant needs a granter,
 * and the first account cannot be created through an endpoint that requires an account. This runner is
 * that seed.
 *
 * <h2>A blank password creates nothing, and that is the safe choice</h2>
 *
 * <p>With no configured password the runner logs a warning and does nothing, leaving a system nobody can
 * sign in to. That sounds unhelpful and is the right default: <b>a system with no way in is safer than a
 * system with a shared default way in.</b> A well-known fallback password is discovered once and then
 * works everywhere it was never changed.
 *
 * <h2>The database is what guarantees "exactly one"</h2>
 *
 * <p>The existence check below is an optimisation, not the guarantee. Two instances starting together both
 * see no ROOT and both attempt an insert — the partial unique index on {@code tenancy = 'ROOT'} is what
 * makes the second fail. That failure is caught and treated as success, because "another instance created
 * it first" is the expected outcome of a normal rolling start, not an error worth refusing to boot over.
 *
 * <h2>It must change its password</h2>
 *
 * <p>The account is created with {@code mustChangePassword}, because the configured value has necessarily
 * been seen by whoever deployed it and may be sitting in a shell history or a CI variable. Without the
 * flag, the first password would be permanent.
 */
/*
 * Gated off under no-database. This runner needs repositories, and repositories need an entity manager.
 *
 * The annotation is required HERE, not just on the module config, because internal/config is the one
 * package the assembler's component scan deliberately reaches — that is how a module registers its own
 * beans. So a @Component in this package is registered by the assembler whatever the module config says.
 */
@Component
@Profile("!no-database")
@Slf4j
public class RootBootstrap implements ApplicationRunner {

    private final StaffIdentityRepository identities;
    private final StaffCredentialRepository credentials;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String email;
    private final String password;

    public RootBootstrap(StaffIdentityRepository identities, StaffCredentialRepository credentials,
            PasswordEncoder passwordEncoder,
            @Value("${identity.bootstrap.root.username:root}") String username,
            @Value("${identity.bootstrap.root.email:root@bus-core.local}") String email,
            @Value("${identity.bootstrap.root.password:}") String password) {
        this.identities = identities;
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (password == null || password.isBlank()) {
            log.warn("No identity.bootstrap.root.password configured, so no ROOT account was created. "
                    + "Nobody can sign in until one exists. Set IDENTITY_BOOTSTRAP_ROOT_PASSWORD and restart.");
            return;
        }

        if (identities.existsByTenancy(StaffTenancy.ROOT)) {
            log.debug("A ROOT account already exists; bootstrap skipped.");
            return;
        }

        try {
            StaffIdentity root = identities.save(StaffIdentity.ofPlatform(
                    username, email, "Root", StaffTenancy.ROOT, AccountStatus.ACTIVE));
            credentials.save(StaffCredential.of(root, passwordEncoder.encode(password), true));
            log.warn("Created the break-glass ROOT account '{}'. It must change its password at first "
                    + "sign-in, and a sign-in by it should be rare enough to alert on.", username);
        } catch (DataIntegrityViolationException anotherInstanceWonTheRace) {
            // The partial unique index refused a second ROOT. Expected during a rolling start; the other
            // instance created it, which is precisely the outcome wanted.
            log.info("Another instance created the ROOT account first; bootstrap skipped.");
        }
    }
}
