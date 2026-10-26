package io.perms;

/**
 * Options for the list helpers. Immutable; each {@code with} method returns a copy.
 *
 * <pre>{@code
 * permio.listPermissions(ListOptions.defaults().withSearch("docs").withPageSize(50));
 * }</pre>
 */
public final class ListOptions {
  private static final ListOptions DEFAULTS = new ListOptions(null, PagedIterable.MAX_PAGE_SIZE);

  private final String search;
  private final int pageSize;

  private ListOptions(String search, int pageSize) {
    this.search = search;
    this.pageSize = pageSize;
  }

  /** No search filter, 100 items per page. */
  public static ListOptions defaults() {
    return DEFAULTS;
  }

  /** Filters by name, where the RPC supports it. */
  public ListOptions withSearch(String search) {
    return new ListOptions(search == null || search.isEmpty() ? null : search, pageSize);
  }

  /** Items requested per page, 1..100. Default 100. */
  public ListOptions withPageSize(int pageSize) {
    return new ListOptions(search, Math.max(1, Math.min(PagedIterable.MAX_PAGE_SIZE, pageSize)));
  }

  /** The search filter, or null. */
  public String search() {
    return search;
  }

  /** Items requested per page. */
  public int pageSize() {
    return pageSize;
  }
}
