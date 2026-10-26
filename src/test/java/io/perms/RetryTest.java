package io.perms;

import static io.perms.TestServer.reply;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Any;
import com.google.protobuf.Empty;
import com.google.rpc.RetryInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import io.perms.proto.permissions.v1.CheckPermissionRequest;
import io.perms.proto.permissions.v1.CheckPermissionResponse;
import io.perms.proto.permissions.v1.GrantUserPermissionsAndRolesOnResourceRequest;
import io.perms.proto.permissions.v1.PermissionsServiceGrpc;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RetryTest {
  private TestServer server;
  private Permio permio;

  @AfterEach
  void tearDown() {
    if (permio != null) {
      permio.close();
    }
    if (server != null) {
      server.close();
    }
  }

  private static final CheckPermissionResponse ALLOWED =
      CheckPermissionResponse.newBuilder().setPassed(true).build();

  /**
   * Check and Grant fakes driven by a script: each call takes the next outcome (a status exception,
   * null to hang, or success); when the script runs out, the last outcome repeats.
   */
  private static final class Scripted extends PermissionsServiceGrpc.PermissionsServiceImplBase {
    private final Deque<Supplier<StatusRuntimeException>> script = new ArrayDeque<>();
    private Supplier<StatusRuntimeException> last;

    Scripted then(Supplier<StatusRuntimeException> outcome) {
      script.add(outcome);
      return this;
    }

    Scripted fail(Status status) {
      return then(status::asRuntimeException);
    }

    Scripted fail(Status status, Metadata trailers) {
      return then(() -> status.asRuntimeException(trailers));
    }

    Scripted hang() {
      return then(() -> null);
    }

    Scripted succeed() {
      return then(() -> new StatusRuntimeException(Status.OK));
    }

    private synchronized Supplier<StatusRuntimeException> next() {
      if (!script.isEmpty()) {
        last = script.poll();
      }
      return last;
    }

    private <T> void answer(StreamObserver<T> o, T value) {
      StatusRuntimeException e = next().get();
      if (e == null) {
        return; // hang
      }
      if (e.getStatus().isOk()) {
        reply(o, () -> value);
      } else {
        o.onError(e);
      }
    }

    @Override
    public void check(CheckPermissionRequest r, StreamObserver<CheckPermissionResponse> o) {
      answer(o, ALLOWED);
    }

    @Override
    public void grantUserPermissionsAndRolesOnResource(
        GrantUserPermissionsAndRolesOnResourceRequest r, StreamObserver<Empty> o) {
      answer(o, Empty.getDefaultInstance());
    }
  }

  private void start(Scripted svc, RetryPolicy policy, Duration timeout) {
    server = new TestServer(svc);
    permio = server.client(b -> b.retryPolicy(policy).timeout(timeout));
  }

  private void start(Scripted svc) {
    start(svc, TestServer.FAST, Permio.DEFAULT_TIMEOUT);
  }

  @Test
  void retriesUnavailableReads() {
    start(new Scripted().fail(Status.UNAVAILABLE).fail(Status.UNAVAILABLE).succeed());
    assertThat(permio.check("alice", "docs.read", "/docs/1")).isTrue();
    assertThat(server.count("Check")).isEqualTo(3);
  }

  @Test
  void givesUpAfterMaxRetries() {
    start(new Scripted().fail(Status.UNAVAILABLE.withDescription("down")));
    assertThatThrownBy(() -> permio.check("alice", "docs.read", "/docs/1"))
        .isInstanceOf(PermioUnavailableException.class)
        .hasMessage("down");
    assertThat(server.count("Check")).isEqualTo(3);
  }

  @Test
  void retriesCanBeDisabled() {
    start(new Scripted().fail(Status.UNAVAILABLE), RetryPolicy.NONE, Permio.DEFAULT_TIMEOUT);
    assertThatThrownBy(() -> permio.check("alice", "docs.read", "/docs/1"))
        .isInstanceOf(PermioUnavailableException.class);
    assertThat(server.count("Check")).isEqualTo(1);
  }

  @Test
  void doesNotRetryOtherCodes() {
    start(new Scripted().fail(Status.INTERNAL));
    assertThatThrownBy(() -> permio.check("alice", "docs.read", "/docs/1"))
        .isExactlyInstanceOf(PermioException.class);
    assertThat(server.count("Check")).isEqualTo(1);
  }

  @Test
  void neverRetriesWrites() {
    start(new Scripted().fail(Status.UNAVAILABLE));
    assertThatThrownBy(() -> permio.grant("alice", "docs.read", "/docs"))
        .isInstanceOf(PermioUnavailableException.class);
    assertThat(server.count("GrantUserPermissionsAndRolesOnResource")).isEqualTo(1);
  }

  @Test
  void perAttemptTimeoutIsRetriedForReads() {
    start(new Scripted().hang().succeed(), TestServer.FAST, Duration.ofMillis(100));
    assertThat(permio.check("alice", "docs.read", "/docs/1")).isTrue();
    assertThat(server.count("Check")).isEqualTo(2);
  }

  @Test
  void perAttemptTimeoutFailsWritesOnce() {
    start(new Scripted().hang(), TestServer.FAST, Duration.ofMillis(100));
    assertThatThrownBy(() -> permio.grant("alice", "docs.read", "/docs"))
        .isInstanceOf(PermioTimeoutException.class)
        .hasMessage("request timed out after 100ms");
    assertThat(server.count("GrantUserPermissionsAndRolesOnResource")).isEqualTo(1);
  }

  @Test
  void callTimeoutBoundsAllAttempts() {
    RetryPolicy slow =
        RetryPolicy.builder()
            .maxRetries(10)
            .baseDelay(Duration.ofMillis(100))
            .maxDelay(Duration.ofMillis(400))
            .build();
    start(new Scripted().fail(Status.UNAVAILABLE), slow, Permio.DEFAULT_TIMEOUT);
    long t0 = System.nanoTime();
    assertThatThrownBy(
            () -> permio.withCallTimeout(Duration.ofMillis(300)).check("alice", "r", "/d"))
        .isInstanceOf(PermioTimeoutException.class);
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(1000);
    assertThat(server.count("Check")).isLessThan(5);
  }

  private static StatusRuntimeException rateLimited(Duration delay) {
    return StatusProto.toStatusRuntimeException(
        com.google.rpc.Status.newBuilder()
            .setCode(Status.Code.RESOURCE_EXHAUSTED.value())
            .setMessage("rate limited")
            .addDetails(
                Any.pack(
                    RetryInfo.newBuilder()
                        .setRetryDelay(
                            com.google.protobuf.Duration.newBuilder()
                                .setNanos((int) delay.toNanos()))
                        .build()))
            .build());
  }

  private static final RetryPolicy ONE_SECOND =
      RetryPolicy.builder()
          .maxRetries(2)
          .baseDelay(Duration.ofMillis(1))
          .maxDelay(Duration.ofSeconds(1))
          .build();

  @Test
  void rateLimitedReadsWaitForRetryInfo() {
    start(
        new Scripted().then(() -> rateLimited(Duration.ofMillis(150))).succeed(),
        ONE_SECOND,
        Permio.DEFAULT_TIMEOUT);
    long t0 = System.nanoTime();
    assertThat(permio.check("alice", "docs.read", "/docs/1")).isTrue();
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isGreaterThanOrEqualTo(150);
    assertThat(server.count("Check")).isEqualTo(2);
  }

  @Test
  void rateLimitBeyondMaxDelayFailsFast() {
    Metadata m = new Metadata();
    m.put(RetryDelays.RETRY_AFTER, "5");
    start(new Scripted().fail(Status.RESOURCE_EXHAUSTED, m), ONE_SECOND, Permio.DEFAULT_TIMEOUT);
    assertThatThrownBy(() -> permio.check("alice", "docs.read", "/docs/1"))
        .isInstanceOfSatisfying(
            PermioRateLimitException.class,
            e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(5)));
    assertThat(server.count("Check")).isEqualTo(1);
  }

  @Test
  void rateLimitWithoutDelayIsNotRetried() {
    start(new Scripted().fail(Status.RESOURCE_EXHAUSTED), ONE_SECOND, Permio.DEFAULT_TIMEOUT);
    assertThatThrownBy(() -> permio.check("alice", "docs.read", "/docs/1"))
        .isInstanceOfSatisfying(
            PermioRateLimitException.class, e -> assertThat(e.retryAfter()).isEmpty());
    assertThat(server.count("Check")).isEqualTo(1);
  }

  @Test
  void rateLimitedWritesAreNotRetried() {
    start(
        new Scripted().then(() -> rateLimited(Duration.ofMillis(10))),
        ONE_SECOND,
        Permio.DEFAULT_TIMEOUT);
    assertThatThrownBy(() -> permio.grant("alice", "docs.read", "/docs"))
        .isInstanceOf(PermioRateLimitException.class);
    assertThat(server.count("GrantUserPermissionsAndRolesOnResource")).isEqualTo(1);
  }

  @Test
  void retryAfterOnUnavailableIsTheMinimumBackoff() {
    Metadata m = new Metadata();
    m.put(RetryDelays.RETRY_AFTER, "1");
    start(
        new Scripted().fail(Status.UNAVAILABLE, m).succeed(),
        RetryPolicy.builder()
            .baseDelay(Duration.ofMillis(1))
            .maxDelay(Duration.ofSeconds(2))
            .build(),
        Permio.DEFAULT_TIMEOUT);
    long t0 = System.nanoTime();
    assertThat(permio.check("alice", "docs.read", "/docs/1")).isTrue();
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isGreaterThanOrEqualTo(1000);
  }

  @Test
  void rawStubsGetAuthAndRetries() {
    start(new Scripted().fail(Status.UNAVAILABLE).succeed());
    CheckPermissionResponse res =
        permio
            .raw()
            .permissions()
            .check(
                CheckPermissionRequest.newBuilder()
                    .setProjectName("my-app")
                    .setPrincipalId("alice")
                    .addResourceUris("/d")
                    .addPermissions("p")
                    .build());
    assertThat(res.getPassed()).isTrue();
    assertThat(server.count("Check")).isEqualTo(2);
    assertThat(server.calls)
        .allSatisfy(c -> assertThat(c.authorization()).isEqualTo("Bearer " + TestServer.VALID_KEY));
  }

  @Test
  void rawFutureStubCanBeCancelledDuringBackoff() throws Exception {
    RetryPolicy slow =
        RetryPolicy.builder()
            .maxRetries(5)
            .baseDelay(Duration.ofMillis(500))
            .maxDelay(Duration.ofMillis(500))
            .build();
    start(new Scripted().fail(Status.UNAVAILABLE), slow, Permio.DEFAULT_TIMEOUT);
    var future =
        permio
            .raw()
            .permissionsFuture()
            .check(
                CheckPermissionRequest.newBuilder()
                    .setProjectName("my-app")
                    .setPrincipalId("alice")
                    .addResourceUris("/d")
                    .addPermissions("p")
                    .build());
    Thread.sleep(100);
    assertThat(future.cancel(true)).isTrue();
    Thread.sleep(700);
    assertThat(server.count("Check")).isEqualTo(1);
  }

  @Test
  void idempotentAllowlist() {
    assertThat(RetryPolicy.isIdempotent("permio.permissions.v1.PermissionsService/Check")).isTrue();
    assertThat(RetryPolicy.isIdempotent("permio.permissions.v1.PermissionsService.WhoAmI"))
        .isTrue();
    for (String write :
        List.of(
            "GrantUserPermissionsAndRolesOnResource",
            "RevokeUserPermissionsAndRolesOnResource",
            "CreatePermission",
            "DeleteGroup",
            "AddGroupMembers")) {
      assertThat(RetryPolicy.isIdempotent("permio.permissions.v1.PermissionsService/" + write))
          .as(write)
          .isFalse();
    }
    assertThat(RetryPolicy.isIdempotent(null)).isFalse();
  }
}
