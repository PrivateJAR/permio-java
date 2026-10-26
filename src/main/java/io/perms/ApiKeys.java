package io.perms;

import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Local checks of the API key format, so a typo is caught before any request is made.
 *
 * <p>Opaque keys are {@code "perms_"} + 32 random base62 characters + 6 base62 characters encoding
 * the CRC32 (IEEE) of those 32, 44 characters in all. Legacy JWT keys are only checked for the
 * three-part shape. Passing these checks does not mean the key exists or is still valid; only the
 * API can tell.
 */
public final class ApiKeys {
  /** Length of an opaque {@code perms_} key. */
  public static final int API_KEY_LENGTH = 44;

  private static final String PREFIX = "perms_";
  private static final int RANDOM_LENGTH = 32;
  private static final int CHECKSUM_LENGTH = 6;
  private static final String ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final Pattern JWT =
      Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");

  /** The kind of API key. */
  public enum Kind {
    /** A {@code perms_...} key from the dashboard. */
    OPAQUE,
    /** A legacy JWT key. */
    JWT
  }

  private ApiKeys() {}

  /**
   * Checks that {@code key} looks like a perms.io API key and returns its kind.
   *
   * @throws PermioConfigException if the key is missing or malformed
   */
  public static Kind validate(String key) {
    if (key == null || key.isEmpty()) {
      throw new PermioConfigException("permio: apiKey is required");
    }
    if (key.startsWith(PREFIX)) {
      if (key.length() != API_KEY_LENGTH) {
        throw new PermioConfigException(
            "permio: malformed API key (expected "
                + API_KEY_LENGTH
                + " characters starting with \""
                + PREFIX
                + "\", got "
                + key.length()
                + ")");
      }
      String rest = key.substring(PREFIX.length());
      for (int i = 0; i < rest.length(); i++) {
        if (ALPHABET.indexOf(rest.charAt(i)) < 0) {
          throw new PermioConfigException(
              "permio: malformed API key (characters after perms_ must be 0-9, A-Z or a-z)");
        }
      }
      if (!checksum(rest.substring(0, RANDOM_LENGTH)).equals(rest.substring(RANDOM_LENGTH))) {
        throw new PermioConfigException(
            "permio: API key checksum mismatch; check it was copied correctly");
      }
      return Kind.OPAQUE;
    }
    if (JWT.matcher(key).matches()) {
      return Kind.JWT;
    }
    throw new PermioConfigException(
        "permio: malformed API key (expected a \"perms_\" key from the dashboard, or a legacy JWT"
            + " key)");
  }

  /** True if {@code key} passes {@link #validate(String)}. */
  public static boolean isValid(String key) {
    try {
      validate(key);
      return true;
    } catch (PermioConfigException e) {
      return false;
    }
  }

  private static String checksum(String body) {
    CRC32 crc = new CRC32();
    // The body is base62, so every character is a single byte.
    for (int i = 0; i < body.length(); i++) {
      crc.update(body.charAt(i));
    }
    long value = crc.getValue();
    char[] out = new char[CHECKSUM_LENGTH];
    for (int i = CHECKSUM_LENGTH - 1; i >= 0; i--) {
      out[i] = ALPHABET.charAt((int) (value % 62));
      value /= 62;
    }
    return new String(out);
  }
}
