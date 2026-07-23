package tz.co.otapp.buscore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;

import lombok.extern.slf4j.Slf4j;

/**
 * The single deployable. Which role it plays is decided by the active Spring profile.
 *
 * <pre>
 * java -jar bus-core.jar --spring.profiles.active=api       # HTTP surface, the default
 * java -jar bus-core.jar --spring.profiles.active=worker    # no HTTP; sweeps and consumers
 * java -jar bus-core.jar --spring.profiles.active=gateway   # edge role, its own port
 * </pre>
 *
 * <p>This class sits at {@code tz.co.otapp.buscore}, the base package every module hangs beneath
 * ({@code tz.co.otapp.buscore.booking}, {@code …fleet}, and so on). The scan therefore covers the whole
 * system from one root, and finds each module's {@code internal.config} class without this class ever
 * naming an internal type — the hiding rule holds for the assembler too.
 *
 * <p><strong>But the scan stops at the config.</strong> Everything else under {@code internal} is
 * excluded, so a module's services, entities, repositories, controllers and security beans are registered
 * by <em>that module's own configuration</em>, never by this scan reaching past it.
 *
 * <p>That distinction is load-bearing, and it is invisible to ArchUnit: a component scan crosses a module
 * boundary by <em>string</em>, not by type reference, so no boundary rule sees it. Without this filter, a
 * module configuration annotated {@code @Profile} decides nothing — the assembler has already registered
 * the beans that configuration was gating.
 *
 * <p>It is worth installing now rather than later. Today every module is empty, so the filter excludes
 * nothing and costs nothing; retrofitting it after twenty modules have shipped beans means discovering
 * which of them were only ever registered by accident.
 */
// @SpringBootApplication expanded into its three parts, because it exposes no excludeFilters attribute
// — that lives on @ComponentScan. The first two filters are the ones @SpringBootApplication installs for
// you; dropping either breaks test slices and re-registers auto-configurations as ordinary beans.
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = "tz.co.otapp.buscore",
        excludeFilters = {
                @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
                @Filter(type = FilterType.REGEX,
                        pattern = "tz\\.co\\.otapp\\.buscore\\..*\\.internal\\.(service|security|api|entity|repository)\\..*")
        })
@Slf4j
public class BusCoreApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(BusCoreApplication.class, args);
        log.info("bus-core started with role(s): {}", String.join(",", rolesOf(context.getEnvironment())));
    }

    /**
     * The roles actually in effect.
     *
     * <p>{@code getActiveProfiles()} returns EMPTY when nobody passed {@code --spring.profiles.active},
     * even though {@code spring.profiles.default: api} means the api role is fully in effect — so logging
     * it directly prints "none" on the single most common way this process is started. The one line in the
     * log that says which role you are running then says it wrong, which is worse than not logging it.
     */
    private static String[] rolesOf(org.springframework.core.env.Environment environment) {
        String[] active = environment.getActiveProfiles();
        return active.length > 0 ? active : environment.getDefaultProfiles();
    }
}
