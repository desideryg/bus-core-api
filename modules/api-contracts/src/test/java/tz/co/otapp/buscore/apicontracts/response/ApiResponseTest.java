package tz.co.otapp.buscore.apicontracts.response;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tz.co.otapp.buscore.apicontracts.error.CommonErrors;
import tz.co.otapp.buscore.apicontracts.paging.PageMeta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The envelope's invariants — the ones a client is entitled to rely on.
 *
 * <p>These are cheap to assert and expensive to lose. Every one of them exists so a client can write a
 * single deserialiser and a single error path; a regression in any would not break a build anywhere else,
 * it would just quietly hand some caller a shape they were never promised.
 */
class ApiResponseTest {

    @Test
    @DisplayName("success is derived from the status, not from what was passed")
    void success_is_derived() {
        // The canonical constructor is reachable — from Jackson, from a test, from a careless refactor —
        // so the invariant is enforced in the compact constructor rather than trusted at every call site.
        ApiResponse<String> lying = new ApiResponse<>(true, 500, "X", "m", null, List.of(), null, null);

        assertThat(lying.success()).isFalse();
    }

    @Test
    @DisplayName("a 4xx is not a success even if constructed as one")
    void client_errors_are_failures() {
        assertThat(new ApiResponse<>(true, 404, "X", "m", null, List.of(), null, null).success()).isFalse();
        assertThat(new ApiResponse<>(false, 200, "OK", "m", null, List.of(), null, null).success()).isTrue();
    }

    @Test
    @DisplayName("errors is never null, so a client can iterate unconditionally")
    void errors_is_never_null() {
        assertThat(new ApiResponse<>(true, 200, "OK", "m", null, null, null, null).errors()).isEmpty();
        assertThat(ApiResponse.ok("payload").errors()).isEmpty();
    }

    @Test
    @DisplayName("errors is defensively copied, so a caller cannot mutate a response after building it")
    void errors_is_immutable() {
        List<ErrorDetail> mutable = new ArrayList<>();
        mutable.add(ErrorDetail.of("CODE", "first"));

        ApiResponse<Void> response = ApiResponse.failure(CommonErrors.VALIDATION_FAILED, "m", mutable);
        mutable.add(ErrorDetail.of("CODE", "sneaked in later"));

        assertThat(response.errors()).hasSize(1);
        assertThatThrownBy(() -> response.errors().add(ErrorDetail.of("X", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a success carries the OK code, so clients branch on code uniformly")
    void success_carries_ok_code() {
        assertThat(ApiResponse.ok("payload").code()).isEqualTo(ApiResponse.OK);
        assertThat(ApiResponse.ok("payload").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a failure takes its status and code from the ErrorCode, not the call site")
    void failure_derives_status_from_the_code() {
        ApiResponse<Void> response = ApiResponse.failure(CommonErrors.NOT_FOUND, "No such booking.");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("COMMON.NOT_FOUND");
        assertThat(response.data()).isNull();
    }

    @Test
    @DisplayName("a void operation returns 200 with a null payload, never 204")
    void void_operations_still_carry_an_envelope() {
        ApiResponse<Void> response = ApiResponse.done("Deleted.");

        // 204 would be the one case where the envelope is absent, forcing every client to grow a second
        // code path for "no body" — the second shape this type exists to prevent.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNull();
    }

    @Test
    @DisplayName("withTraceId preserves every other field")
    void trace_id_is_additive() {
        ApiResponse<String> original = ApiResponse.ok("payload", "Done");

        ApiResponse<String> stamped = original.withTraceId("abc123");

        assertThat(stamped.traceId()).isEqualTo("abc123");
        assertThat(stamped.data()).isEqualTo("payload");
        assertThat(stamped.message()).isEqualTo("Done");
        assertThat(stamped.statusCode()).isEqualTo(original.statusCode());
        assertThat(original.traceId()).as("the original is untouched").isNull();
    }

    @Test
    @DisplayName("a page reports its numbers in meta, leaving data a plain list")
    void paging_lives_in_meta() {
        ApiResponse<List<String>> response = ApiResponse.page(List.of("a", "b"), PageMeta.of(0, 20, 137));

        assertThat(response.data()).containsExactly("a", "b");
        assertThat(response.meta().totalPages()).isEqualTo(7);
        assertThat(response.meta().last()).isFalse();
    }

    @Test
    @DisplayName("PageMeta computes last and totalPages one way, in one place")
    void page_meta_arithmetic() {
        assertThat(PageMeta.of(6, 20, 137).last()).as("the seventh of seven pages").isTrue();
        assertThat(PageMeta.of(0, 20, 20).totalPages()).as("an exact fit is one page").isEqualTo(1);
        assertThat(PageMeta.of(0, 20, 21).totalPages()).as("one over spills to a second").isEqualTo(2);

        PageMeta empty = PageMeta.of(0, 20, 0);
        assertThat(empty.totalPages()).as("no results is zero pages, not one").isZero();
        assertThat(empty.last()).as("the only page of an empty result is the last one").isTrue();
    }
}
