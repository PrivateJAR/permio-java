package io.perms;

import io.perms.proto.pagination.v1.PaginationRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Every item of a paginated list RPC, fetched lazily one page at a time as you iterate. Each call
 * to {@link #iterator()} or {@link #stream()} starts again from the first page.
 *
 * <p>Following {@code next_cursor} stops when the API returns an empty cursor. A cursor the API
 * already returned would loop forever, so it is an error rather than a silent end of the listing.
 * API errors are thrown from {@code hasNext()} as {@link PermioException}s.
 *
 * @param <T> the item type
 */
public final class PagedIterable<T> implements Iterable<T> {
  /** Largest page the API accepts ({@code PaginationRequest.limit} is 1..100). */
  public static final int MAX_PAGE_SIZE = 100;

  /** One page of results. */
  public static final class Page<T> {
    final List<T> items;
    final String nextCursor;

    /** Creates a page. */
    public Page(List<T> items, String nextCursor) {
      this.items = items;
      this.nextCursor = nextCursor == null ? "" : nextCursor;
    }
  }

  private final Function<PaginationRequest, Page<T>> fetchPage;
  private final int pageSize;

  /**
   * Iterates any list RPC that uses {@code PaginationRequest} / {@code PaginationResponse}.
   *
   * <pre>{@code
   * new PagedIterable<>(
   *     p -> {
   *       ListAPIKeysResponse res = permio.raw().organisations().listAPIKeys(
   *           ListAPIKeysRequest.newBuilder().setPagination(p)...build());
   *       return new PagedIterable.Page<>(res.getApiKeysList(), res.getPagination().getNextCursor());
   *     },
   *     100);
   * }</pre>
   *
   * @param fetchPage fetches one page for the given pagination request
   * @param pageSize items per page, clamped to 1..100
   */
  public PagedIterable(Function<PaginationRequest, Page<T>> fetchPage, int pageSize) {
    this.fetchPage = fetchPage;
    this.pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
  }

  @Override
  public Iterator<T> iterator() {
    return new PageIterator();
  }

  /** A sequential stream over every item. */
  public Stream<T> stream() {
    return StreamSupport.stream(
        Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED), false);
  }

  /** Fetches every page and returns all items. */
  public List<T> toList() {
    List<T> out = new ArrayList<>();
    forEach(out::add);
    return Collections.unmodifiableList(out);
  }

  private final class PageIterator implements Iterator<T> {
    private final Set<String> seen = new HashSet<>();
    private Iterator<T> current = Collections.emptyIterator();
    private String cursor;
    private boolean last;

    @Override
    public boolean hasNext() {
      while (!current.hasNext()) {
        if (last) {
          return false;
        }
        PaginationRequest.Builder req = PaginationRequest.newBuilder().setLimit(pageSize);
        if (cursor != null) {
          req.setCursor(cursor);
        }
        Page<T> page;
        try {
          page = fetchPage.apply(req.build());
        } catch (RuntimeException e) {
          throw PermioException.from(e);
        }
        current = page.items.iterator();
        String next = page.nextCursor;
        // An empty cursor ends the listing. A repeated one would loop forever, and stopping there
        // would silently return a partial list, so fail instead.
        if (next.isEmpty()) {
          last = true;
        } else if (!seen.add(next)) {
          throw new PermioException(
              "permio: inconsistent response from the API: pagination cursor repeated; the"
                  + " listing would not end");
        } else {
          cursor = next;
        }
      }
      return true;
    }

    @Override
    public T next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return current.next();
    }
  }
}
