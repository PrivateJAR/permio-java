package io.perms;

/**
 * A collect-all helper ({@link Permio#whoHasAccess}, {@link Permio#listAccess}) found more results
 * than its {@code maxResults} cap (default {@link Permio#DEFAULT_MAX_RESULTS}). Nothing beyond the
 * cap plus one item was fetched. Iterate the streaming form ({@link Permio#whoHasAccessIterable},
 * {@link Permio#listAccessIterable}) instead, or pass a larger {@code maxResults}. {@link #code()}
 * is empty: the API did not fail.
 */
public class PermioTooManyResultsException extends PermioException {
  private static final long serialVersionUID = 1L;

  private final int maxResults;

  /** Creates the exception for {@code method} exceeding {@code maxResults}. */
  public PermioTooManyResultsException(String method, String streamingMethod, int maxResults) {
    super(
        "permio: "
            + method
            + " has more than "
            + maxResults
            + " results; iterate "
            + streamingMethod
            + " to stream them, or raise maxResults");
    this.maxResults = maxResults;
  }

  /** The cap that was exceeded. */
  public int maxResults() {
    return maxResults;
  }
}
