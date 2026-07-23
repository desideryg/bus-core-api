package tz.co.otapp.buscore.config;

import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs each module's migrations, with its own history table, in one database.
 *
 * <p>The system is a single deployable over a single database. Modules own their tables but share the
 * schema, so what separates them here is <b>one Flyway history table per module</b>: identity-access
 * versions its migrations independently of every other module, and adding a module cannot renumber
 * anybody else's.
 *
 * <h2>Four things here are load-bearing, and each fails quietly if omitted</h2>
 *
 * <p><b>1 — Boot's own migration is switched off</b> ({@code spring.flyway.enabled: false} in the yml).
 * Left on, it runs a single default instance against {@code flyway_schema_history} and fights this one:
 * two mechanisms migrating the same database with different histories.
 *
 * <p><b>2 — {@code baselineVersion("0")}.</b> Flyway's default baseline is version 1, which marks V1 as
 * already applied and <b>silently skips every module's first migration</b>. Nothing errors; the tables
 * simply never exist, and the failure surfaces later as a validation error about a missing table.
 *
 * <p><b>3 — The launcher classloader.</b> Module migrations live inside nested jars in the repackaged
 * deployable. Flyway's default classloader cannot see them there, so it finds nothing — and finding
 * nothing is not an error to Flyway. It works perfectly from an IDE and applies no migrations in
 * production, which is the worst possible split. The emptiness check below turns that silence into a
 * startup failure.
 *
 * <p><b>4 — The ordering guarantee.</b> Hibernate's {@code ddl-auto=validate} and this migration runner
 * are both beans, and without a declared dependency their order is decided by instantiation sequence.
 * Validation then sometimes runs before the tables exist — <b>intermittently</b>, which is far worse than
 * consistently, because it passes on a developer's machine and fails on a deploy. The post-processor at
 * the bottom makes the entity manager factory depend on this runner, so the order is a fact rather than
 * an accident.
 */
/*
 * Gated off under no-database: there is no DataSource to migrate.
 */
@Configuration
@Profile("!no-database")
@Slf4j
public class FlywayMigrationsConfig {

    /**
     * Modules that own migrations, in the order they run.
     *
     * <p>Order matters only where one module's schema references another's. Today there is one entry;
     * it is a list because the second module is the one that would otherwise reveal all of this was
     * hard-coded for a single case.
     */
    private static final List<ModuleMigrations> MODULES = List.of(
            new ModuleMigrations("identity-access", "identityaccess"));

    /**
     * @param moduleName the module's artifact name, used to name its history table
     * @param location   the migration folder under {@code db/migration}. Dashes are stripped, matching the
     *                   package convention — the folder for {@code identity-access} is {@code identityaccess}
     */
    private record ModuleMigrations(String moduleName, String location) {

        /** {@code flyway_schema_history_identity_access} — underscored, unlike the folder. */
        String historyTable() {
            return "flyway_schema_history_" + moduleName.replace('-', '_');
        }
    }

    /**
     * Migrates every module at startup and returns the number applied.
     *
     * <p>Returns a value rather than being {@code void} so the bean has something for the post-processor
     * below to depend on by name.
     */
    @Bean
    public MigrationsApplied moduleMigrations(DataSource dataSource) {
        int total = 0;
        for (ModuleMigrations module : MODULES) {
            Flyway flyway = Flyway.configure(getClass().getClassLoader())   // trap 3
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/" + module.location())
                    .table(module.historyTable())
                    .baselineOnMigrate(true)
                    .baselineVersion("0")                                   // trap 2
                    .load();

            int discovered = flyway.info().all().length;
            if (discovered == 0) {
                // Trap 3's detector. Flyway treats "no migrations found" as success, so without this the
                // application starts happily against an unmigrated database.
                throw new IllegalStateException(
                        "No migrations found for module '" + module.moduleName() + "' at classpath:db/migration/"
                                + module.location() + ". Flyway does not treat this as an error, so it would "
                                + "otherwise start against an unmigrated database. Check the folder name and "
                                + "that the module jar is on the classpath.");
            }

            int applied = flyway.migrate().migrationsExecuted;
            total += applied;
            log.info("{}: {} migration(s) applied, {} known, history in {}",
                    module.moduleName(), applied, discovered, module.historyTable());
        }
        return new MigrationsApplied(total);
    }

    /** How many migrations ran at startup. A value type so the bean can be depended upon. */
    public record MigrationsApplied(int count) {
    }

    /**
     * Forces the entity manager factory to be created <em>after</em> migrations have run.
     *
     * <p>This is trap 4, and it is the one most likely to be dismissed as unnecessary — because without it
     * everything usually works. "Usually" is the problem: the order is otherwise decided by bean
     * instantiation sequence, so validation runs before migration on some startups and not others.
     */
    @Bean
    public static EntityManagerFactoryDependsOnPostProcessor migrationsBeforeEntityManager() {
        return new EntityManagerFactoryDependsOnPostProcessor("moduleMigrations") {
        };
    }
}
