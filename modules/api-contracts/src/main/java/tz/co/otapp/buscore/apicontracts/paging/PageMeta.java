package tz.co.otapp.buscore.apicontracts.paging;

/**
 * The paging block of a response envelope.
 *
 * <p>Nested under {@code meta} rather than flattened alongside the payload. Nesting keeps {@code data} a
 * clean array and gives paging room to grow — a cursor, an approximate-total flag — without adding
 * top-level keys that are meaningless on every non-paged response.
 *
 * <p>There is deliberately <b>no flat variant</b>. Supporting both is the worst of the three options:
 * clients implement each forever, and nobody can tell which endpoints return which without trying them.
 *
 * @param pageNumber   zero-based, matching the page number the caller asked for
 * @param pageSize     the size actually applied, which may be smaller than requested if the request
 *                     exceeded the cap — reporting the requested size would be a lie the caller then
 *                     paginates against
 * @param totalElements total rows matching the query across all pages
 * @param totalPages   total pages at this size; {@code 0} when there are no results, not {@code 1}
 * @param last         whether this is the final page. Present because it is what a client actually
 *                     branches on, and deriving it correctly from the other three is a small thing every
 *                     client would otherwise get slightly wrong
 */
public record PageMeta(
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean last) {

    /**
     * Build from the numbers a repository page reports.
     *
     * <p>Kept as a factory rather than a constructor call at each site so that {@code last} is computed one
     * way, in one place.
     */
    public static PageMeta of(int pageNumber, int pageSize, long totalElements) {
        int totalPages = pageSize <= 0 ? 0 : (int) Math.ceil((double) totalElements / pageSize);
        boolean last = pageNumber >= totalPages - 1;
        return new PageMeta(pageNumber, pageSize, totalElements, totalPages, last);
    }
}
