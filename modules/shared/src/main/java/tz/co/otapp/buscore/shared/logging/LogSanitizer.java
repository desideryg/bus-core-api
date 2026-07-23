package tz.co.otapp.buscore.shared.logging;

/**
 * Neutralises caller-controlled text before it reaches a log line.
 *
 * <h2>The attack</h2>
 *
 * <p>A log line is newline-delimited, so a value containing a newline does not appear <em>in</em> a log
 * entry — it <em>ends</em> one and starts another. Given
 *
 * <pre>
 * log.warn("login failed for {}", username);
 * </pre>
 *
 * a caller who submits the username
 *
 * <pre>
 * bob\n2026-07-23 10:14:02 INFO  admin granted ROOT to attacker
 * </pre>
 *
 * has written a second, entirely fabricated log entry. The record you would rely on during an incident
 * becomes the thing the attacker authored — and unlike most injection, nothing malfunctions, so nobody
 * notices until the log is being read in anger.
 *
 * <p>Carriage returns matter for the same reason on Windows-formatted logs, and a naive terminal or log
 * viewer can also be driven by escape sequences.
 *
 * <h2>Using it</h2>
 *
 * <pre>
 * log.warn("login failed for {}", LogSanitizer.clean(username));
 * </pre>
 *
 * <p>Wrap <b>every</b> value that originated outside the process: request parameters, header values, body
 * fields, and anything read back from a database that a caller once supplied. A value that has been through
 * a database is not clean — it is exactly as clean as it was when it was stored.
 *
 * <p>Keep this the only implementation. Where a static-analysis tool is told to treat a sanitiser as
 * trusted, that configuration names one class; a second copy elsewhere would not be recognised, and code
 * using it would be flagged while code using this one is not.
 */
public final class LogSanitizer {

    /** What a stripped character is replaced with, so its removal is visible rather than silent. */
    private static final String REPLACEMENT = "_";

    private LogSanitizer() {
    }

    /**
     * Returns the value with anything that could forge a log entry replaced.
     *
     * <p>Strips carriage returns, newlines, and the other C0 control characters — including the escape
     * character, which a terminal would otherwise interpret rather than print.
     *
     * @param value the untrusted text, which may be null
     * @return the sanitised text, or the literal {@code "null"} so a log line never reads
     *         {@code "login failed for "} with nothing after it and leaves a reader unsure whether the
     *         value was empty, absent, or lost
     */
    public static String clean(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder cleaned = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            // C0 controls (including \r, \n, \t and ESC) plus DEL. Everything printable passes through
            // unchanged, so ordinary text — including non-Latin scripts — is untouched.
            cleaned.append(character < 0x20 || character == 0x7F ? REPLACEMENT : character);
        }
        return cleaned.toString();
    }

    /**
     * Sanitises and caps length, for values that are attacker-controlled in <em>size</em> as well as
     * content.
     *
     * <p>A megabyte header value logged in full is a cheap way to fill a disk or bury the surrounding
     * entries.
     *
     * @param maxLength maximum characters to keep; the result is suffixed with {@code …} when truncated
     */
    public static String clean(String value, int maxLength) {
        String cleaned = clean(value);
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength) + "…";
    }
}
