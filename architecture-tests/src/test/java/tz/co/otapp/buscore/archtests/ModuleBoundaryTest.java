package tz.co.otapp.buscore.archtests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * {@code internal} means internal.
 *
 * <p>Each module publishes its surface at its package root ({@code tz.co.otapp.buscore.booking}) and hides
 * everything else beneath {@code …booking.internal}. Java has no way to say that: {@code public} is public
 * to the whole classpath, and once {@code booking} is a declared dependency of {@code charter}, every
 * public type in it — entity, repository, service impl — is one import away.
 *
 * <p>Which is the failure this rule is actually about. It is not that someone reads a class they should
 * not have; it is that a refactor of an internal entity three years from now silently breaks a module
 * nobody thought was looking. A published port is a promise; an internal type is not, and the difference
 * has to be enforced somewhere other than reviewer memory.
 *
 * <p>The rule is generated over {@link ModuleCatalog#DAG}, so a module added to the catalog is policed the
 * same day, without anyone writing a rule for it.
 *
 * <p><strong>The one legitimate crossing</strong> is the assembler's component scan, which reaches each
 * module's {@code internal.config} by <em>string</em> rather than by type reference (see
 * BusCoreApplication). No bytecode dependency exists, so this rule neither sees it nor should.
 */
class ModuleBoundaryTest {

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("tz.co.otapp.buscore");
    }

    @Test
    @DisplayName("no class outside a module may reach into that module's internal package")
    void module_internals_are_private_to_their_module() {
        JavaClasses classes = productionClasses();

        for (String module : ModuleCatalog.DAG.keySet()) {
            String root = ModuleCatalog.packageRootOf(module);

            noClasses()
                    .that().resideOutsideOfPackage(root + "..")
                    .should().dependOnClassesThat().resideInAPackage(root + ".internal..")
                    // Every module is an empty skeleton today, so there is nothing for this rule to find
                    // yet. That is not a reason to leave it out — it is the reason to put it in now, while
                    // the first slice can be born under it instead of retrofitted into it.
                    .allowEmptyShould(true)
                    .as("no class outside " + module + " may depend on " + root + ".internal")
                    .because("a type under internal/ is not a promise to anyone. Publish what other modules "
                            + "may use at the module's package root; everything else stays hidden so it can "
                            + "be changed without breaking a caller nobody knew existed.")
                    .check(classes);
        }
    }
}
