package io.perms;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Builds and checks resource URIs. */
public final class Resources {
  /** The API rejects resource URIs longer than this many bytes (UTF-8). */
  public static final int MAX_RESOURCE_URI_LENGTH = 2048;

  private static final char[] HEX = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

  // JavaScript's \s (Unicode white space) plus C0/C1 control characters.
  private static final Pattern WHITESPACE_OR_CONTROL =
      Pattern.compile(
          "[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff"
              + "\\x00-\\x1f\\x7f-\\x9f]");

  private Resources() {}

  /**
   * Builds a resource URI from raw segments, percent-encoding each one so that IDs containing
   * {@code /}, spaces or other reserved characters stay a single segment.
   *
   * <pre>{@code
   * Resources.resource("projects", projectId, "docs", "a/b") // "/projects/p1/docs/a%2Fb"
   * }</pre>
   *
   * <p>Build URIs the same way when granting and when checking, or they will not match.
   *
   * @param segments the segments; each is converted with {@code String.valueOf}
   * @throws PermioValidationException if there are no segments or one is null or empty
   */
  public static String resource(Object... segments) {
    if (segments == null || segments.length == 0) {
      throw new PermioValidationException("permio: resource() needs at least one segment");
    }
    StringBuilder sb = new StringBuilder();
    for (Object segment : segments) {
      sb.append('/').append(encodeSegment(segment));
    }
    return sb.toString();
  }

  /**
   * Percent-encodes one resource URI segment: everything except {@code A-Z a-z 0-9 - _ . ~} is
   * encoded as UTF-8 bytes. This matches permio-js, which encodes {@code ! ' ( ) *} on top of
   * {@code encodeURIComponent} because the API rejects {@code ( ) *} in segments.
   *
   * @throws PermioValidationException if the segment is null, empty or not valid Unicode
   */
  public static String encodeSegment(Object segment) {
    if (segment == null) {
      throw new PermioValidationException("permio: resource URI segments must not be null");
    }
    String text = String.valueOf(segment);
    if (text.isEmpty()) {
      throw new PermioValidationException("permio: resource URI segments must not be empty");
    }
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)
          && (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1)))) {
        throw new PermioValidationException("permio: resource URI segment is not valid Unicode");
      }
      if (Character.isLowSurrogate(c)
          && (i == 0 || !Character.isHighSurrogate(text.charAt(i - 1)))) {
        throw new PermioValidationException("permio: resource URI segment is not valid Unicode");
      }
    }
    StringBuilder sb = new StringBuilder(text.length());
    for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      if ((c >= 'A' && c <= 'Z')
          || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || c == '-'
          || c == '_'
          || c == '.'
          || c == '~') {
        sb.append((char) c);
      } else {
        sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xf]);
      }
    }
    return sb.toString();
  }

  /**
   * Throws {@link PermioValidationException} unless {@code uri} has the basic shape the API
   * accepts: a leading {@code /}, no empty segments (so no {@code //} or trailing {@code /}), no
   * whitespace or control characters, at most 2048 bytes. The API applies further rules (for
   * example no regex metacharacters such as {@code ( ) [ ] * + ?} in segments) and answers {@code
   * INVALID_ARGUMENT} when they are broken.
   */
  public static void assertResourceUri(String uri) {
    if (uri == null || uri.isEmpty()) {
      throw invalid(uri, "it is empty");
    }
    if (!uri.startsWith("/")) {
      throw invalid(uri, "it must start with \"/\" (use resource() to build URIs)");
    }
    if (uri.getBytes(StandardCharsets.UTF_8).length > MAX_RESOURCE_URI_LENGTH) {
      throw invalid(uri, "it is longer than " + MAX_RESOURCE_URI_LENGTH + " bytes");
    }
    if (WHITESPACE_OR_CONTROL.matcher(uri).find()) {
      throw invalid(uri, "it contains whitespace or control characters");
    }
    if (uri.equals("/") || uri.endsWith("/") || uri.contains("//")) {
      throw invalid(uri, "it contains an empty segment");
    }
  }

  private static PermioValidationException invalid(String uri, String why) {
    return new PermioValidationException(
        "permio: invalid resource URI " + (uri == null ? "null" : "\"" + uri + "\"") + ": " + why);
  }
}
