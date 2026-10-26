package io.perms;

/** The client was configured incorrectly, e.g. a malformed API key or no project. */
public class PermioConfigException extends PermioException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public PermioConfigException(String message) {
    super(message);
  }
}
