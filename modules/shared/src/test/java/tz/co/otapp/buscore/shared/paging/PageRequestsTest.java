package tz.co.otapp.buscore.shared.paging;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import tz.co.otapp.buscore.apicontracts.error.ApiException;
import tz.co.otapp.buscore.apicontracts.error.CommonErrors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The paging rules, which exist to be the only ones.
 *
 * <p>Two of these tests guard against behaviour that would be an easy, well-meaning "improvement": silently
 * ignoring an unknown sort field, and honouring whatever page size was asked for. Both look like leniency
 * and are respectively a correctness bug and a denial-of-service vector.
 */
class PageRequestsTest {

    private static final Set<String> SORTABLE = Set.of("createdAt", "name");

    @Test
    @DisplayName("absent page and size fall back to the defaults")
    void applies_defaults() {
        Pageable pageable = PageRequests.of(null, null);

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(PageRequests.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("an oversized page is clamped to the cap, not honoured")
    void clamps_oversized_page() {
        Pageable pageable = PageRequests.of(0, 1_000_000);

        assertThat(pageable.getPageSize())
                .as("?size=1000000 is a one-line denial of service that needs no credentials")
                .isEqualTo(PageRequests.MAX_SIZE);
    }

    @Test
    @DisplayName("a negative page is clamped rather than refused")
    void clamps_negative_page() {
        // Deliberately gentler than the sort rules: page zero is unambiguously what the caller wants.
        assertThat(PageRequests.of(-5, null).getPageNumber()).isZero();
    }

    @Test
    @DisplayName("a non-positive size falls back to the default rather than returning nothing")
    void rejects_non_positive_size() {
        assertThat(PageRequests.of(0, 0).getPageSize()).isEqualTo(PageRequests.DEFAULT_SIZE);
        assertThat(PageRequests.of(0, -1).getPageSize()).isEqualTo(PageRequests.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("a permitted sort is applied, with direction")
    void applies_sort() {
        Pageable pageable = PageRequests.of(0, 10, List.of("createdAt,desc"), SORTABLE);

        Sort.Order order = pageable.getSort().getOrderFor("createdAt");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("sort defaults to ascending when no direction is given")
    void defaults_to_ascending() {
        Pageable pageable = PageRequests.of(0, 10, List.of("name"), SORTABLE);

        assertThat(pageable.getSort().getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("an unknown sort property is REFUSED, never silently dropped")
    void refuses_unknown_sort_property() {
        assertThatThrownBy(() -> PageRequests.of(0, 10, List.of("secretColumn"), SORTABLE))
                .isInstanceOf(ApiException.class)
                .as("dropping it returns arbitrarily-ordered results to a caller who asked for an order, "
                        + "and their pagination then repeats and skips rows with nothing looking wrong")
                .hasMessageContaining("sort");
    }

    @Test
    @DisplayName("an unusable direction is refused")
    void refuses_unknown_direction() {
        assertThatThrownBy(() -> PageRequests.of(0, 10, List.of("name,sideways"), SORTABLE))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("every bad sort expression is reported at once, not just the first")
    void reports_all_problems() {
        ApiException thrown = catchThrowableOfType(ApiException.class,
                () -> PageRequests.of(0, 10, List.of("nope", "alsoNope", "name,sideways"), SORTABLE));

        assertThat(thrown.errorCode()).isEqualTo(CommonErrors.VALIDATION_FAILED);
        assertThat(thrown.details())
                .as("reporting one at a time makes a caller submit three times to learn three things")
                .hasSize(3);
    }

    @Test
    @DisplayName("the refusal names what IS sortable")
    void refusal_is_actionable() {
        ApiException thrown = catchThrowableOfType(ApiException.class,
                () -> PageRequests.of(0, 10, List.of("nope"), SORTABLE));

        // A refusal that does not say what to send instead turns a fixable mistake into a support ticket.
        assertThat(thrown.details().getFirst().message()).contains("createdAt").contains("name");
    }

    @Test
    @DisplayName("blank and null sort entries are ignored rather than refused")
    void tolerates_empty_entries() {
        // A trailing comma in a query string is a formatting artefact, not an instruction to be refused.
        Pageable pageable = PageRequests.of(0, 10, List.of("", "  "), SORTABLE);

        assertThat(pageable.getSort().isSorted()).isFalse();
    }
}
