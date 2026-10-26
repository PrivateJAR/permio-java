package io.perms;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Any;
import com.google.rpc.Code;
import com.google.rpc.RetryInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class ErrorsTest {
  private static PermioException map(Status status) {
    return PermioException.from(status.asRuntimeException());
  }

  @Test
  void mapsCodesToTypes() {
    assertThat(map(Status.UNAUTHENTICATED)).isInstanceOf(PermioAuthException.class);
    assertThat(map(Status.PERMISSION_DENIED)).isInstanceOf(PermioPermissionException.class);
    assertThat(map(Status.INVALID_ARGUMENT)).isInstanceOf(PermioValidationException.class);
    assertThat(map(Status.NOT_FOUND)).isInstanceOf(PermioNotFoundException.class);
    assertThat(map(Status.UNAVAILABLE)).isInstanceOf(PermioUnavailableException.class);
    assertThat(map(Status.DEADLINE_EXCEEDED)).isInstanceOf(PermioTimeoutException.class);
    assertThat(map(Status.RESOURCE_EXHAUSTED)).isInstanceOf(PermioRateLimitException.class);
    PermioException other = map(Status.ALREADY_EXISTS.withDescription("exists"));
    assertThat(other.getClass()).isEqualTo(PermioException.class);
    assertThat(other.code()).contains(ErrorCode.ALREADY_EXISTS);
    assertThat(other.grpcCode()).contains(Status.Code.ALREADY_EXISTS);
    assertThat(other.codeName()).contains("already_exists");
    assertThat(other.getMessage()).isEqualTo("exists");
  }

  @Test
  void keepsMessageCauseAndMetadata() {
    Metadata trailers = new Metadata();
    Metadata.Key<String> key = Metadata.Key.of("x-test", Metadata.ASCII_STRING_MARSHALLER);
    trailers.put(key, "1");
    var sre = Status.NOT_FOUND.withDescription("no such group").asRuntimeException(trailers);
    PermioException e = PermioException.from(sre);
    assertThat(e.getMessage()).isEqualTo("no such group");
    assertThat(e.getCause()).isSameAs(sre);
    assertThat(e.metadata().get(key)).isEqualTo("1");
  }

  @Test
  void parsesMissingPermissions() {
    String msg =
        "missing permissions: [{ResourceURI:/organisation/o/project/p/doc"
            + " MissingPermissions:[permission.check other]}"
            + " {ResourceURI:/organisation/o/project/p MissingPermissions:[]}]";
    PermioException e = map(Status.PERMISSION_DENIED.withDescription(msg));
    assertThat(((PermioPermissionException) e).missing())
        .containsExactly(
            new MissingPermission(
                "/organisation/o/project/p/doc", List.of("permission.check", "other")),
            new MissingPermission("/organisation/o/project/p", List.of()));
    assertThat(PermioException.parseMissingPermissions("nothing here")).isEmpty();
  }

  @Test
  void readsRetryInfo() {
    com.google.rpc.Status details =
        com.google.rpc.Status.newBuilder()
            .setCode(Code.RESOURCE_EXHAUSTED_VALUE)
            .setMessage("slow down")
            .addDetails(
                Any.pack(
                    RetryInfo.newBuilder()
                        .setRetryDelay(
                            com.google.protobuf.Duration.newBuilder()
                                .setSeconds(1)
                                .setNanos(250_000_000))
                        .build()))
            .build();
    PermioException e = PermioException.from(StatusProto.toStatusRuntimeException(details));
    assertThat(e).isInstanceOf(PermioRateLimitException.class);
    assertThat(((PermioRateLimitException) e).retryAfter()).contains(Duration.ofMillis(1250));
    assertThat(e.getMessage()).isEqualTo("slow down");
  }

  @Test
  void readsRetryAfterHeader() {
    Metadata m = new Metadata();
    m.put(RetryDelays.RETRY_AFTER, "3");
    var e =
        (PermioRateLimitException)
            PermioException.from(Status.RESOURCE_EXHAUSTED.asRuntimeException(m));
    assertThat(e.retryAfter()).contains(Duration.ofSeconds(3));

    Metadata date = new Metadata();
    date.put(
        RetryDelays.RETRY_AFTER,
        DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().plusSeconds(30)));
    var d =
        (PermioRateLimitException)
            PermioException.from(Status.RESOURCE_EXHAUSTED.asRuntimeException(date));
    assertThat(d.retryAfter().orElseThrow())
        .isBetween(Duration.ofSeconds(28), Duration.ofSeconds(30));

    var none = (PermioRateLimitException) map(Status.RESOURCE_EXHAUSTED);
    assertThat(none.retryAfter()).isEmpty();
  }

  @Test
  void unwrapsFutureExceptions() {
    var sre = Status.UNAVAILABLE.asRuntimeException();
    assertThat(PermioException.from(new ExecutionException(sre)))
        .isInstanceOf(PermioUnavailableException.class);
    assertThat(PermioException.from(new CompletionException(new PermioConfigException("x"))))
        .isInstanceOf(PermioConfigException.class);
    PermioException unknown = PermioException.from(new IllegalStateException("boom"));
    assertThat(unknown.code()).contains(ErrorCode.UNKNOWN);
    assertThat(unknown.getMessage()).isEqualTo("boom");
  }

  @Test
  void deniedListsWhatIsMissing() {
    var e =
        new PermioDeniedException(
            "alice", List.of(new MissingPermission("/docs/1", List.of("docs.read", "docs.write"))));
    assertThat(e.getMessage())
        .isEqualTo("principal \"alice\" is missing docs.read, docs.write on /docs/1");
    assertThat(e.code()).isEmpty();
    assertThat(e.grpcCode()).isEmpty();
    assertThat(e.principal()).isEqualTo("alice");
  }

  @Test
  void errorCodeMirrorsEveryGrpcErrorCode() {
    for (Status.Code c : Status.Code.values()) {
      if (c != Status.Code.OK) {
        assertThat(ErrorCode.of(c).name()).isEqualTo(c.name());
      }
    }
    assertThat(ErrorCode.of(Status.Code.OK)).isEqualTo(ErrorCode.UNKNOWN);
  }
}
