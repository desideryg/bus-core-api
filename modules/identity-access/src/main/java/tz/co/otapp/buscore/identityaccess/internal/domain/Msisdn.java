package tz.co.otapp.buscore.identityaccess.internal.domain;

/**
 * One phone number, one spelling.
 *
 * <p>{@code 0712 345 678}, {@code 255712345678} and {@code +255712345678} are the same handset. Stored as
 * typed, they are three accounts; looked up as typed, a person who signed up one way and signs in another
 * is told their credentials are invalid, and nothing anywhere explains why.
 *
 * <p>So a number is canonicalised on the way in and only the canonical form is ever stored or compared —
 * the same problem the staff tables solve with a functional index on {@code lower(username)}, solved here
 * in Java because no SQL expression canonicalises a phone number.
 *
 * <h2>It is deliberately not a full phone-number library</h2>
 *
 * <p>libphonenumber knows every numbering plan on earth and would be the right answer if this system took
 * numbers from everywhere. It takes them from one country, from handsets held by agents this business has
 * signed up, and a dependency that large earns its place by solving a problem that is actually present.
 *
 * <p>What it does <b>not</b> do is validate that a number is reachable. Nothing here can — only sending to
 * it proves that, which is the {@code notification} module's business in a later wave.
 *
 * <h2>Local, for now</h2>
 *
 * <p>The {@code customer} module will need exactly this and will pull it up into {@code shared} when it
 * does. It stays here until there is a second caller, because a type used by one module and placed in the
 * artifact everything depends on turns a one-module change into a full rebuild.
 */
public final class Msisdn {

    /** Tanzania. The only plan this system serves today, and the assumption a second country would break. */
    private static final String COUNTRY_CODE = "255";

    /** National significant number: 9 digits after the country code, the first being the operator prefix. */
    private static final int NATIONAL_DIGITS = 9;

    private Msisdn() {
    }

    /**
     * The canonical E.164 form, or null when the input cannot be one.
     *
     * <p><b>Returns null rather than throwing</b>, because every caller is on an authentication path where
     * a malformed identifier must be indistinguishable from an unknown one. Throwing would make a badly
     * formatted number answer differently from a well formatted number that belongs to nobody — and that
     * difference is an oracle: it tells a caller which of their guesses were even plausible.
     *
     * @param raw whatever the caller typed, or null
     * @return {@code +255XXXXXXXXX}, or null if it is not a number this system can serve
     */
    public static String canonical(String raw) {
        if (raw == null) {
            return null;
        }

        // Everything a person or a keypad might insert for legibility. Removed rather than rejected: a
        // number typed with spaces is a correct number typed by a human being.
        String digits = raw.replaceAll("[\\s\\-()./]", "");

        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        if (!digits.matches("\\d+")) {
            return null;
        }

        // 0712345678 — the national form, how it is written on a business card.
        if (digits.length() == NATIONAL_DIGITS + 1 && digits.startsWith("0")) {
            digits = COUNTRY_CODE + digits.substring(1);
        }

        // 255712345678, with or without the plus. Already international, just missing the marker.
        if (digits.length() != COUNTRY_CODE.length() + NATIONAL_DIGITS
                || !digits.startsWith(COUNTRY_CODE)) {
            return null;
        }

        // A national number never starts with 0 once the trunk prefix is gone. 25507... is somebody who
        // pasted both forms together, and it is not a phone number.
        if (digits.charAt(COUNTRY_CODE.length()) == '0') {
            return null;
        }

        return "+" + digits;
    }
}
