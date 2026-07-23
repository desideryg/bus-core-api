package tz.co.otapp.buscore.archtests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The module DAG, made real.
 *
 * <p>"The modules are independent" is the kind of claim a README makes and nothing checks. Maven will not
 * check it: every internal {@code <dependency>} is plain compile scope, so a module's whole transitive
 * closure lands on its compile classpath. Declare {@code scheduling} and you can silently import
 * {@code network}, {@code tenancy} and {@code staff} without ever naming them. The compiler is happy, the
 * build is green, and the architecture is quietly gone.
 *
 * <p>So three rules, and the third is the one with teeth:
 *
 * <ol>
 *   <li>{@link #the_poms_and_the_declared_dag_agree()} — {@link ModuleCatalog#DAG} is the DAG, and it must
 *       match what the poms actually declare, <em>in both directions</em>. A pom edge missing from the map
 *       is a dependency nobody decided on; a map edge missing from the pom is a lie in the architecture.</li>
 *   <li>{@link #the_dag_is_acyclic()} — a cycle welds two modules together permanently. Maven catches a
 *       reactor cycle only once it exists; this catches it in the declaration.</li>
 *   <li>{@link #no_module_references_a_module_it_does_not_declare()} — <strong>bytecode, not poms.</strong>
 *       Declaring an edge set is worthless if the classpath lets you ignore it.</li>
 * </ol>
 *
 * <p>Every module is empty today, so rule 3 currently passes over almost nothing. That is the cheapest
 * moment to install it: the alternative is discovering, at module fifteen, which of the fourteen edges
 * already taken were decisions and which were accidents.
 */
class ModuleDependencyTest {

    /**
     * Matches an internal dependency in a module pom. The groupId is spelled out rather than wildcarded on
     * purpose: it must mirror the base Java package ({@code tz.co.otapp.buscore}), and a pattern loose
     * enough to match either spelling would let the two drift apart silently — which is the state this
     * scaffold was briefly in.
     */
    private static final Pattern INTERNAL_DEPENDENCY = Pattern.compile(
            "<groupId>tz\\.co\\.otapp\\.buscore</groupId>\\s*<artifactId>([a-z-]+)</artifactId>");

    private static Set<String> declaredInPom(String module) throws IOException {
        Path pom = Path.of("..", "modules", module, "pom.xml");
        assertThat(pom).as("module %s has no pom", module).exists();
        String xml = Files.readString(pom, StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        Set<String> declared = new TreeSet<>();
        Matcher m = INTERNAL_DEPENDENCY.matcher(xml);
        while (m.find()) {
            String artifact = m.group(1);
            // The <parent> block names the reactor root, and a module naming itself is not an edge.
            if (!artifact.equals(ModuleCatalog.REACTOR_ROOT) && !artifact.equals(module)) {
                declared.add(artifact);
            }
        }
        return declared;
    }

    @Test
    @DisplayName("each module's pom and the declared DAG agree, in both directions")
    void the_poms_and_the_declared_dag_agree() throws IOException {
        for (Map.Entry<String, Set<String>> entry : ModuleCatalog.DAG.entrySet()) {
            String module = entry.getKey();

            assertThat(declaredInPom(module))
                    .as("""
                            The pom and the DAG disagree for module '%s'.

                            An edge in the pom but not in the DAG is a dependency nobody decided on — it arrived in \
                            some commit and no one had to argue for it. An edge in the DAG but not in the pom is a \
                            lie: the architecture claims a relationship the build does not have.

                            Declare the dependency in BOTH, in the same commit, with a reason.""", module)
                    .containsExactlyInAnyOrderElementsOf(entry.getValue());
        }
    }

    @Test
    @DisplayName("every module on disk is in the DAG, and vice versa")
    void every_reactor_module_is_in_the_dag() throws IOException {
        Set<String> onDisk = new TreeSet<>();
        try (var dirs = Files.list(Path.of("..", "modules"))) {
            dirs.filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("pom.xml")))
                    .forEach(d -> onDisk.add(d.getFileName().toString()));
        }

        assertThat(ModuleCatalog.DAG.keySet())
                .as("A module exists in the reactor but not in the DAG (or vice versa). A module outside the "
                        + "DAG is a module whose dependencies nobody is checking.")
                .containsExactlyInAnyOrderElementsOf(onDisk);
    }

    @Test
    @DisplayName("the DAG is acyclic")
    void the_dag_is_acyclic() {
        // A cycle welds two modules into one deployable forever: neither can be extracted, and a change to
        // either recompiles both. Maven would catch a reactor cycle only once it exists; this catches it in
        // the declaration, which is where it is still an argument rather than a migration.
        for (String module : ModuleCatalog.DAG.keySet()) {
            assertThatCode(() -> assertNoCycleFrom(module))
                    .as("the module DAG must be acyclic")
                    .doesNotThrowAnyException();
        }
    }

    private static void assertNoCycleFrom(String start) {
        walk(start, new ArrayDeque<>(), new HashSet<>());
    }

    private static void walk(String module, Deque<String> path, Set<String> settled) {
        if (settled.contains(module)) {
            return;
        }
        if (path.contains(module)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(module);
            throw new AssertionError("Dependency cycle: " + String.join(" -> ", cycle));
        }
        path.push(module);
        for (String next : ModuleCatalog.DAG.getOrDefault(module, Set.of())) {
            walk(next, path, settled);
        }
        path.pop();
        settled.add(module);
    }

    @Test
    @DisplayName("no module references a module it does not declare (bytecode)")
    void no_module_references_a_module_it_does_not_declare() {
        // The rule with teeth. Every internal dependency is compile-scoped, so a module's transitive closure
        // is importable from it whether or not its pom says so. This reads compiled classes, so what a module
        // may touch is what it declared — not whatever Maven happened to drag along behind it.
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("tz.co.otapp.buscore");

        for (Map.Entry<String, Set<String>> entry : ModuleCatalog.DAG.entrySet()) {
            String module = entry.getKey();
            Set<String> allowed = entry.getValue();

            Set<String> forbiddenPackages = new LinkedHashSet<>();
            for (String other : ModuleCatalog.DAG.keySet()) {
                if (!other.equals(module) && !allowed.contains(other)) {
                    forbiddenPackages.add(ModuleCatalog.packageRootOf(other) + "..");
                }
            }
            if (forbiddenPackages.isEmpty()) {
                continue;
            }

            noClasses()
                    .that().resideInAPackage(ModuleCatalog.packageRootOf(module) + "..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(forbiddenPackages.toArray(String[]::new))
                    .allowEmptyShould(true)
                    .because("module '" + module + "' may only reference the modules it declares in its pom "
                            + "(see ModuleCatalog.DAG). Maven puts the transitive closure on the compile "
                            + "classpath, so the compiler will NOT stop you taking an undeclared edge — this "
                            + "rule is the only thing that does. If the edge is right, declare it in the pom "
                            + "and in the DAG, in the same commit.")
                    .check(classes);
        }
    }
}
