package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tz.co.otapp.buscore.identityaccess.internal.domain.enums.AccountStatus;

/**
 * How an account's access is being withdrawn.
 *
 * <p>The caller must say <em>which</em> withdrawal they mean rather than the surface picking one, because
 * the two answer different questions: suspension asks "should this person be working today", blocking asks
 * "should this account ever be used again". Defaulting would force whoever comes to reverse it to guess
 * which had been intended.
 *
 * @param status the resulting status — only {@code SUSPENDED} or {@code BLOCKED}. {@code ACTIVE} is reached
 *               by deleting the suspension, which is a different permission; {@code PENDING} is a state an
 *               account is born in and cannot be returned to
 * @param reason free text for the audit trail. Optional, because requiring it produces "n/a" rather than
 *               insight, and an account that needs cutting off at 3am should not wait on a text field
 */
public record SuspendStaffRequest(

        @NotNull(message = "Say whether the account is being suspended or blocked.")
        AccountStatus status,

        @Size(max = 256, message = "A reason may be at most 256 characters.")
        String reason) {

    /**
     * Only the two withdrawals are reachable here.
     *
     * <p>Validated rather than checked in the service so it comes back as a 400 naming the field, alongside
     * every other malformed-request complaint, instead of a bespoke error the caller has to learn. Null
     * passes, because {@link #status}'s own {@code @NotNull} is what reports that — two annotations firing
     * on one absent field produces two messages for one mistake.
     */
    @AssertTrue(message = "An account can only be suspended or blocked here; restore it to make it active.")
    public boolean isWithdrawal() {
        return status == null || status == AccountStatus.SUSPENDED || status == AccountStatus.BLOCKED;
    }
}
