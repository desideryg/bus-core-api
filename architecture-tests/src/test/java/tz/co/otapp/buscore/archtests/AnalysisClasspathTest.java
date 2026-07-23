package tz.co.otapp.buscore.archtests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every module in the reactor is a declared dependency of this one — so every rule in
 * {@link ModuleBoundaryTest} and {@link ModuleDependencyTest} actually <em>sees</em> it.
 *
 * <p><strong>Why this is not paranoia.</strong> ArchUnit is silent about code it cannot see, and what it
 * can see is decided entirely by one pom. A module added to {@code modules/} but forgotten in
 * {@code architecture-tests/pom.xml} is analysed by nothing, and the build reports success — which is
 * worse than having no rule at all, because a rule that silently checks nothing is still trusted.
 *
 * <p>The failure mode is not hypothetical and it is not always a fresh module: a module can be visible
 * only <em>transitively</em>, because some unrelated module happens to depend on it. Drop that edge in a
 * refactor and the coverage vanishes with it. A coverage guarantee that rests on someone else's pom is not
 * a guarantee.
 */
class AnalysisClasspathTest {

    /**
     * Modules deliberately left out of the analysis. <strong>Ships empty, and is enforced empty.</strong>
     *
     * <p>It exists as a place to record a decision, not as a place to park one. An entry here means "this
     * module's boundaries are checked by nothing" — a sentence somebody has to be willing to write down
     * next to a module name.
     */
    private static final Set<String> DELIBERATELY_UNANALYSED = Set.of();

    private static final Pattern REACTOR_MODULE = Pattern.compile("<module>modules/([a-z-]+)</module>");
    private static final Pattern DECLARED_DEPENDENCY =
            Pattern.compile("<artifactId>([a-z-]+)</artifactId>\\s*<version>\\$\\{project\\.version}</version>");

    @Test
    @DisplayName("every reactor module under modules/ is on the analysis classpath")
    void every_module_is_analysed() throws IOException {
        Set<String> reactorModules = matches(REACTOR_MODULE, read(Path.of("..", "pom.xml")));
        Set<String> analysed = matches(DECLARED_DEPENDENCY, read(Path.of("pom.xml")));

        assertThat(reactorModules)
                .as("read no <module>modules/…</module> entries from the reactor pom — this test is reading the "
                        + "wrong file and would pass vacuously")
                .isNotEmpty();

        Set<String> missing = new TreeSet<>(reactorModules);
        missing.removeAll(analysed);
        missing.removeAll(DELIBERATELY_UNANALYSED);

        assertThat(missing)
                .as("""
                        These reactor modules are NOT declared dependencies of architecture-tests, so ArchUnit \
                        cannot see them and EVERY rule is silently green on them. Add each to \
                        architecture-tests/pom.xml. A module may be omitted only by naming it in \
                        DELIBERATELY_UNANALYSED, which means writing down that nothing checks it.""")
                .isEmpty();
    }

    @Test
    @DisplayName("the unanalysed list is empty")
    void nothing_is_deliberately_unanalysed() {
        assertThat(DELIBERATELY_UNANALYSED)
                .as("A module has been excused from architectural analysis. That may be right — but it is a "
                        + "decision, and it belongs in a review, not in a constant that grew an entry to make a "
                        + "red build green.")
                .isEmpty();
    }

    private static String read(Path relativeToModuleBasedir) throws IOException {
        return Files.readString(relativeToModuleBasedir.toAbsolutePath().normalize(), StandardCharsets.UTF_8);
    }

    private static Set<String> matches(Pattern pattern, String content) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
