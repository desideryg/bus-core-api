package tz.co.otapp.buscore.identityaccess.internal.config;

// Spring Boot 4 split the monolithic autoconfigure artifact, so @EntityScan now lives in
// spring-boot-persistence rather than org.springframework.boot.autoconfigure.domain. An import written
// from Boot 3 habit simply will not resolve.
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Registers this module's beans, entities and repositories.
 *
 * <p><b>This class is the module's only door into the application context.</b> The assembler scans
 * {@code tz.co.otapp.buscore} but excludes everything under any module's {@code internal} package except
 * {@code internal.config} — so it finds this configuration and nothing else, and this configuration
 * registers the rest.
 *
 * <p>The indirection is load-bearing and invisible to the architecture rules, because a component scan
 * crosses a module boundary by <em>string</em> rather than by type reference. Without the exclusion, the
 * assembler would already have registered every service and controller here, and any future
 * {@code @Profile} on this class would decide nothing — the beans it was gating would exist regardless.
 *
 * <p>Practical consequence: a {@code @Service} in this module that is not reachable from this scan is not
 * registered. That is the design working.
 */
/*
 * Absent under the no-database profile, because this module OWNS TABLES and cannot run without one. Its
 * repositories need an EntityManagerFactory, and the bootstrap runner needs its repositories — so with
 * persistence excluded the context fails to start rather than starting degraded.
 *
 * That profile exists so the web and error-handling layer can still be tested without a container. A
 * module that persists nothing would not need this annotation; this one does, and saying so here is
 * cheaper than discovering it from a bean-creation stack trace.
 */
@Configuration
@Profile("!no-database")
@ComponentScan(basePackages = {
        "tz.co.otapp.buscore.identityaccess.internal.service",
        "tz.co.otapp.buscore.identityaccess.internal.api"
})
@EnableJpaRepositories(basePackages = "tz.co.otapp.buscore.identityaccess.internal.repository")
@EntityScan(basePackages = "tz.co.otapp.buscore.identityaccess.internal.domain.entity")
public class IdentityAccessModuleConfig {
}
