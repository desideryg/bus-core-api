package tz.co.otapp.buscore.shared.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Log-forging defence.
 *
 * <p>The failure this prevents is unusual in that nothing malfunctions when it happens: the application
 * keeps working, the response is normal, and the only damage is that the log now contains entries an
 * attacker wrote. Nobody notices until the log is being read during an incident — which is exactly when it
 * matters and exactly when it is too late.
 */
class LogSanitizerTest {

    @Test
    @DisplayName("a newline cannot end the log entry and start a forged one")
    void strips_newlines() {
        String forged = "bob\n2026-07-23 10:14:02 INFO  admin granted ROOT to attacker";

        String cleaned = LogSanitizer.clean(forged);

        assertThat(cleaned).doesNotContain("\n");
        assertThat(cleaned).startsWith("bob_");
    }

    @Test
    @DisplayName("carriage returns are stripped too")
    void strips_carriage_returns() {
        assertThat(LogSanitizer.clean("a\r\nb")).isEqualTo("a__b");
    }

    @Test
    @DisplayName("escape sequences a terminal would interpret are stripped")
    void strips_control_characters() {
        // ESC would otherwise let a value repaint or clear the screen of anyone tailing the log.
        assertThat(LogSanitizer.clean("a[31mred")).isEqualTo("a_[31mred_");
        assertThat(LogSanitizer.clean("tab\there")).isEqualTo("tab_here");
    }

    @Test
    @DisplayName("ordinary text, including non-Latin scripts, passes through untouched")
    void leaves_printable_text_alone() {
        assertThat(LogSanitizer.clean("Habari, Dar es Salaam! 123 +255")).isEqualTo("Habari, Dar es Salaam! 123 +255");
        assertThat(LogSanitizer.clean("日本語")).isEqualTo("日本語");
    }

    @Test
    @DisplayName("null becomes a visible literal rather than an empty gap")
    void renders_null_visibly() {
        // "login failed for " with nothing after it leaves a reader unable to tell whether the value was
        // empty, absent, or lost on the way to the log.
        assertThat(LogSanitizer.clean(null)).isEqualTo("null");
    }

    @Test
    @DisplayName("an oversized value is truncated")
    void truncates_to_the_cap() {
        String huge = "x".repeat(10_000);

        // A megabyte header logged in full is a cheap way to fill a disk or bury the surrounding entries.
        assertThat(LogSanitizer.clean(huge, 100)).hasSize(101).endsWith("…");
    }

    @Test
    @DisplayName("a value within the cap is not marked as truncated")
    void does_not_mark_short_values() {
        assertThat(LogSanitizer.clean("short", 100)).isEqualTo("short");
    }
}
