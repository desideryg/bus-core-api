package tz.co.otapp.buscore.identityaccess.internal.domain.enums;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tz.co.otapp.buscore.identityaccess.StaffTenancy;
import tz.co.otapp.buscore.apicontracts.enums.DescribedEnum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The contract every described enum owes, checked over every constant of every enum in this module.
 *
 * <p>One parameterised test rather than one per enum, so a new enum is covered by adding a single line to
 * {@link #allConstants()} — a suite that has to be extended in several places for each addition is a suite
 * that eventually is not.
 */
class DomainEnumsTest {

    /** Every constant of every described enum this module declares. Add new enums here. */
    static Stream<DescribedEnum> allConstants() {
        return Stream.of(
                Stream.of(StaffTenancy.values()),
                Stream.of(AccountStatus.values()),
                Stream.of(SessionRevocationReason.values()))
                .flatMap(s -> s.map(DescribedEnum.class::cast));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allConstants")
    @DisplayName("value equals the constant name, because persistence writes name()")
    void value_matches_constant(DescribedEnum constant) {
        // If these ever diverged, the database and the API would describe one concept with two different
        // codes — and nothing else in the build would notice.
        assertThatCode(constant::assertValueMatchesConstant).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allConstants")
    @DisplayName("the label is human copy, distinct from the code")
    void label_is_present_and_not_the_code(DescribedEnum constant) {
        assertThat(constant.getName()).isNotBlank();
        // Catches the copy-paste that sets the label to the constant name and quietly turns a UI into a
        // wall of SHOUTING_SNAKE_CASE.
        assertThat(constant.getName())
                .as("%s should carry a human label, not its own code", constant.name())
                .isNotEqualTo(constant.name());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allConstants")
    @DisplayName("every constant explains itself")
    void description_is_a_sentence(DescribedEnum constant) {
        // A description exists to answer "which of these do I pick?" for whoever is looking at a dropdown.
        // A blank or one-word one answers nothing, so the bar is a real sentence.
        assertThat(constant.getDescription()).isNotBlank().hasSizeGreaterThan(20);
    }
}
