package io.perms;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.RetryInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Reads the delay a server asked for before a retry. */
final class RetryDelays {
  static final Metadata.Key<String> RETRY_AFTER =
      Metadata.Key.of("retry-after", Metadata.ASCII_STRING_MARSHALLER);

  private RetryDelays() {}

  /**
   * How long the server asked the client to wait before retrying: a {@code google.rpc.RetryInfo}
   * error detail, else a {@code retry-after} header (seconds or an HTTP date). Null when it gave no
   * delay.
   */
  static Duration serverRetryDelay(Status status, Metadata metadata) {
    if (metadata == null) {
      return null;
    }
    try {
      com.google.rpc.Status details = StatusProto.fromStatusAndTrailers(status, metadata);
      if (details != null) {
        for (Any any : details.getDetailsList()) {
          if (any.is(RetryInfo.class)) {
            com.google.protobuf.Duration d = any.unpack(RetryInfo.class).getRetryDelay();
            long ms = d.getSeconds() * 1000 + (d.getNanos() + 999_999) / 1_000_000;
            return Duration.ofMillis(Math.max(0, ms));
          }
        }
      }
    } catch (InvalidProtocolBufferException | RuntimeException e) {
      // Malformed detail: ignore it.
    }
    String header = metadata.get(RETRY_AFTER);
    if (header == null || header.trim().isEmpty()) {
      return null;
    }
    header = header.trim();
    if (header.matches("\\d+")) {
      try {
        return Duration.ofSeconds(Long.parseLong(header));
      } catch (NumberFormatException e) {
        return null;
      }
    }
    try {
      Instant at = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      Duration d = Duration.between(Instant.now(), at);
      return d.isNegative() ? Duration.ZERO : d;
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
