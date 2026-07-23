package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An agent signing in.
 *
 * <p>A phone number and a PIN, because these are field sellers with handsets — a username would be one
 * more thing to lose, and a password one more thing to type on a keypad in a bus station.
 *
 * <p>The number is accepted in any spelling and canonicalised server-side; the annotations below only
 * bound the input, they do not judge it. <b>Nothing here reports that a number is malformed</b>, and that
 * is the point: an unparseable number and a number belonging to nobody must answer identically, or the
 * difference tells a caller which of their guesses were even plausible.
 *
 * @param msisdn the phone number, however the agent types it
 * @param pin    the PIN. Bounded generously rather than to a fixed length — a length rule enforced on the
 *               way in tells an attacker exactly how large the search space is, and the real place to
 *               enforce it is where a PIN is chosen, which is a later slice
 */
public record AgentLoginRequest(

        @NotBlank(message = "Enter your phone number.")
        @Size(max = 24, message = "That is not a phone number.")
        String msisdn,

        @NotBlank(message = "Enter your PIN.")
        @Size(max = 32, message = "That is not a PIN.")
        String pin) {
}
