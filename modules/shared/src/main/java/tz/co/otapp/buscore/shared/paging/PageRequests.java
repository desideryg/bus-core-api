package tz.co.otapp.buscore.shared.paging;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import tz.co.otapp.buscore.apicontracts.error.ApiException;
import tz.co.otapp.buscore.apicontracts.error.CommonErrors;
import tz.co.otapp.buscore.apicontracts.response.ErrorDetail;

/**
 * Turns the {@code page}, {@code size} and {@code sort} a caller sent into a validated {@link Pageable}.
 *
 * <h2>Why this exists once</h2>
 *
 * <p>Because two implementations disagree. They disagree about the maximum page size, about whether an
 * unknown sort field is ignored or refused, and about the error a malformed request produces — so the same
 * bad input gets two different answers depending on which endpoint it arrived at. That is a contract defect
 * rather than an inconsistency, and it stays invisible until one client hits both.
 *
 * <h2>An unknown sort property is refused, not dropped</h2>
 *
 * <p>The tempting behaviour is to ignore a sort field the entity does not have. Do not: the caller asked for
 * an order and would receive an arbitrary one, with nothing in the response saying so. Their pagination
 * then appears to work while silently repeating and skipping rows — the worst kind of failure, because
 * nothing looks wrong.
 *
 * <p>Refusing requires knowing which properties are sortable, which is why every method takes an explicit
 * allow-list. That list is also a security boundary: without it a caller can order by any column mapped on
 * the entity, and ordering by a column they cannot read still leaks its values, one binary comparison at a
 * time.
 */
public final class PageRequests {

    /** Page size when the caller names none. Large enough to be useful, small enough to stay cheap. */
    public static final int DEFAULT_SIZE = 20;

    /**
     * The largest page anyone may request.
     *
     * <p>A cap, not a suggestion. Without one, {@code ?size=1000000} is a one-line denial of service that
     * needs no credentials and no cleverness — the database materialises the rows, the JVM holds them, and
     * the serialiser writes them.
     */
    public static final int MAX_SIZE = 100;

    private PageRequests() {
    }

    /**
     * Validate and normalise, unsorted.
     *
     * @param page zero-based page number; null means the first page
     * @param size rows per page; null means {@link #DEFAULT_SIZE}
     */
    public static Pageable of(Integer page, Integer size) {
        return PageRequest.of(normalisePage(page), normaliseSize(size));
    }

    /**
     * Validate and normalise, with sorting.
     *
     * @param sort             sort expressions as {@code property} or {@code property,direction} —
     *                         {@code createdAt,desc}. Multiple entries are applied in order.
     * @param sortableProperties the properties a caller may sort by. <b>Required</b>; see the class javadoc
     *                         for why this cannot default to "anything on the entity".
     * @throws ApiException {@code 400 COMMON.VALIDATION_FAILED} if any sort expression is unusable, listing
     *                      every offending expression rather than only the first
     */
    public static Pageable of(Integer page, Integer size, List<String> sort, Set<String> sortableProperties) {
        Sort resolved = resolveSort(sort, sortableProperties);
        return PageRequest.of(normalisePage(page), normaliseSize(size), resolved);
    }

    /**
     * A negative page is clamped rather than refused.
     *
     * <p>Deliberately gentler than the sort rules: page zero is unambiguously what a caller asking for page
     * -1 wants, and returning results costs them nothing. An unresolvable sort has no such obvious
     * intention.
     */
    private static int normalisePage(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }

    /** Absent or non-positive falls back to the default; anything over the cap is clamped to it. */
    private static int normaliseSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private static Sort resolveSort(List<String> sort, Set<String> sortableProperties) {
        if (sort == null || sort.isEmpty()) {
            return Sort.unsorted();
        }

        List<Sort.Order> orders = new ArrayList<>();
        List<ErrorDetail> problems = new ArrayList<>();

        for (String expression : sort) {
            if (expression == null || expression.isBlank()) {
                continue;
            }
            // "property" or "property,direction". Split on the first comma only, so a direction that is
            // itself malformed is reported as a bad direction rather than as a bad property.
            String[] parts = expression.split(",", 2);
            String property = parts[0].trim();

            if (!sortableProperties.contains(property)) {
                // The message names what IS allowed. A refusal that does not tell the caller what to send
                // instead turns a fixable mistake into a support ticket.
                problems.add(ErrorDetail.of("sort", "UNKNOWN_PROPERTY",
                        "Cannot sort by '" + property + "'. Sortable: " + String.join(", ", sortableProperties)));
                continue;
            }

            Sort.Direction direction = Sort.Direction.ASC;
            if (parts.length == 2 && !parts[1].isBlank()) {
                String requested = parts[1].trim();
                // Spring's fromString throws on anything unrecognised; catching it here lets this expression
                // be reported alongside the others rather than aborting the whole loop.
                try {
                    direction = Sort.Direction.fromString(requested);
                } catch (IllegalArgumentException unrecognisedDirection) {
                    problems.add(ErrorDetail.of("sort", "UNKNOWN_DIRECTION",
                            "Sort direction '" + requested + "' is not valid. Use 'asc' or 'desc'."));
                    continue;
                }
            }
            orders.add(new Sort.Order(direction, property));
        }

        if (!problems.isEmpty()) {
            // Every problem at once. Reporting only the first makes a caller with three bad sort fields
            // submit three times to discover three things.
            throw new ApiException(CommonErrors.VALIDATION_FAILED,
                    "The sort parameter could not be applied.", problems);
        }
        return orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
    }
}
