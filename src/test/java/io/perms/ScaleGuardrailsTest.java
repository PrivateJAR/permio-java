package io.perms;

import static io.perms.TestServer.reply;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Any;
import com.google.rpc.RetryInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import io.perms.proto.pagination.v1.PaginationResponse;
import io.perms.proto.permissions.v1.AccessScope;
import io.perms.proto.permissions.v1.AccessScopeKind;
import io.perms.proto.permissions.v1.BatchCheckRequest;
import io.perms.proto.permissions.v1.BatchCheckResponse;
import io.perms.proto.permissions.v1.BatchCheckResult;
import io.perms.proto.permissions.v1.CheckPermissionRequest;
import io.perms.proto.permissions.v1.CheckPermissionResponse;
import io.perms.proto.permissions.v1.ListAccessRequest;
import io.perms.proto.permissions.v1.ListAccessResponse;
import io.perms.proto.permissions.v1.ListPermissionsRequest;
import io.perms.proto.permissions.v1.ListPermissionsResponse;
import io.perms.proto.permissions.v1.ListPrincipalsWithAccessRequest;
import io.perms.proto.permissions.v1.ListPrincipalsWithAccessResponse;
import io.perms.proto.permissions.v1.PermissionsServiceGrpc;
import io.perms.proto.permissions.v1.PrincipalAccess;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Streaming forms, result caps, the batch bound and list-page timeouts/retries. */
class ScaleGuardrailsTest {
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

  private Permio start(
      PermissionsServiceGrpc.PermissionsServiceImplBase service,
      Consumer<Permio.Builder> configure) {
    server = new TestServer(service);
    permio = server.client(configure);
    return permio;
  }

  private Permio start(PermissionsServiceGrpc.PermissionsServiceImplBase service) {
    return start(service, b -> {});
  }

  /**
   * A fake with {@code total} principals with access and {@code total} access scopes, served in
   * pages of the requested limit; the cursor is the next offset. Records every request.
   */
  private static final class Pages extends PermissionsServiceGrpc.PermissionsServiceImplBase {
    final long total;
    final List<ListPrincipalsWithAccessRequest> whoRequests = new CopyOnWriteArrayList<>();
    final List<ListAccessRequest> accessRequests = new CopyOnWriteArrayList<>();
    volatile long served;

    Pages(long total) {
      this.total = total;
    }

    private long offset(String cursor) {
      return cursor.isEmpty() ? 0 : Long.parseLong(cursor);
    }

    @Override
    public void listPrincipalsWithAccess(
        ListPrincipalsWithAccessRequest request,
        StreamObserver<ListPrincipalsWithAccessResponse> o) {
      whoRequests.add(request);
      long from = offset(request.getPagination().getCursor());
      long to = Math.min(total, from + request.getPagination().getLimit());
      ListPrincipalsWithAccessResponse.Builder res = ListPrincipalsWithAccessResponse.newBuilder();
      for (long i = from; i < to; i++) {
        res.addPrincipals(PrincipalAccess.newBuilder().setPrincipalId(String.format("u%07d", i)));
      }
      served += to - from;
      res.setPagination(
          PaginationResponse.newBuilder().setNextCursor(to < total ? Long.toString(to) : ""));
      reply(o, res::build);
    }

    @Override
    public void listAccess(ListAccessRequest request, StreamObserver<ListAccessResponse> o) {
      accessRequests.add(request);
      long from = offset(request.getPagination().getCursor());
      long to = Math.min(total, from + request.getPagination().getLimit());
      ListAccessResponse.Builder res = ListAccessResponse.newBuilder();
      for (long i = from; i < to; i++) {
        res.addScopes(
            AccessScope.newBuilder()
                .setUri("/docs/" + i)
                .setScope(AccessScopeKind.ACCESS_SCOPE_KIND_EXACT));
      }
      served += to - from;
      res.setPagination(
          PaginationResponse.newBuilder().setNextCursor(to < total ? Long.toString(to) : ""));
      reply(o, res::build);
    }
  }

  // -- streaming ----------------------------------------------------------------------------------

  @Test
  void whoHasAccessIterableStreamsEveryPage() {
    Pages pages = new Pages(2_550);
    start(pages);
    PagedIterable<AccessEntry> entries = permio.whoHasAccessIterable("/docs/1");
    assertThat(pages.whoRequests).isEmpty();

    Iterator<AccessEntry> it = entries.iterator();
    for (int i = 0; i < 150; i++) {
      assertThat(it.next().principal()).isEqualTo(String.format("u%07d", i));
    }
    // Lazy: only the pages needed so far were fetched.
    assertThat(pages.whoRequests).hasSize(2);

    assertThat(entries.stream().count()).isEqualTo(2_550);
    assertThat(pages.whoRequests).hasSize(2 + 26);
    assertThat(pages.whoRequests.get(2).getPagination().getLimit()).isEqualTo(100);
  }

  @Test
  void whoHasAccessIterableIsNotCapped() {
    Pages pages = new Pages(Permio.DEFAULT_MAX_RESULTS + 5);
    start(pages);
    assertThat(permio.whoHasAccessIterable("/docs/1", "docs.read", true).stream().count())
        .isEqualTo(Permio.DEFAULT_MAX_RESULTS + 5);
    assertThat(pages.whoRequests.get(0).getPermission()).isEqualTo("docs.read");
    assertThat(pages.whoRequests.get(0).getExpandGroups()).isTrue();
  }

  @Test
  void listAccessIterableStreamsScopes() {
    Pages pages = new Pages(1_234);
    start(pages);
    List<AccessScopes.Scope> scopes =
        permio.listAccessIterable("alice", "docs.read", "/docs").toList();
    assertThat(scopes).hasSize(1_234);
    assertThat(scopes.get(1_233).uri()).isEqualTo("/docs/1233");
    assertThat(scopes.get(0).kind()).isEqualTo(AccessScopes.Kind.EXACT);
    assertThat(pages.accessRequests).hasSize(13);
  }

  @Test
  void listAccessIterableYieldsOneSubtreeScopeWhenCoveringAll() {
    start(
        new PermissionsServiceGrpc.PermissionsServiceImplBase() {
          @Override
          public void listAccess(ListAccessRequest request, StreamObserver<ListAccessResponse> o) {
            reply(
                o,
                () ->
                    ListAccessResponse.newBuilder()
                        .setCoversAll(true)
                        .addScopes(
                            AccessScope.newBuilder()
                                .setUri(request.getUnderUri())
                                .setScope(AccessScopeKind.ACCESS_SCOPE_KIND_SUBTREE))
                        .build());
          }
        });
    List<AccessScopes.Scope> scopes =
        permio.listAccessIterable("alice", "docs.read", "/docs").toList();
    assertThat(scopes).hasSize(1);
    assertThat(scopes.get(0).uri()).isEqualTo("/docs");
    assertThat(scopes.get(0).kind()).isEqualTo(AccessScopes.Kind.SUBTREE);
    assertThat(permio.listAccess("alice", "docs.read", "/docs").coversAll()).isTrue();
  }

  @Test
  void streamingFormsValidateInputs() {
    start(new Pages(0));
    assertThatThrownBy(() -> permio.whoHasAccessIterable("docs"))
        .isInstanceOf(PermioValidationException.class);
    assertThatThrownBy(() -> permio.listAccessIterable("alice", "", "/docs"))
        .isInstanceOf(PermioValidationException.class);
  }

  // -- caps ---------------------------------------------------------------------------------------

  @Test
  void whoHasAccessUnderTheCapReturnsEverything() {
    Pages pages = new Pages(250);
    start(pages);
    assertThat(permio.whoHasAccess("/docs/1", null, false, 250)).hasSize(250);
    // The last request asks only for the one item that would exceed the cap.
    assertThat(pages.whoRequests.stream().map(r -> r.getPagination().getLimit()))
        .containsExactly(100, 100, 51);
  }

  @Test
  void whoHasAccessOverTheCapStopsAtCapPlusOne() {
    Pages pages = new Pages(1_000_000);
    start(pages);
    assertThatThrownBy(() -> permio.whoHasAccess("/docs/1", null, false, 250))
        .isInstanceOfSatisfying(
            PermioTooManyResultsException.class,
            e -> {
              assertThat(e.maxResults()).isEqualTo(250);
              assertThat(e.code()).isEmpty();
              assertThat(e.getMessage()).contains("250").contains("whoHasAccessIterable");
            });
    assertThat(pages.served).isEqualTo(251);
    assertThat(pages.whoRequests).hasSize(3);
  }

  @Test
  void whoHasAccessDefaultCapIs10000() {
    Pages pages = new Pages(1_000_000);
    start(pages);
    assertThatThrownBy(() -> permio.whoHasAccess("/docs/1"))
        .isInstanceOfSatisfying(
            PermioTooManyResultsException.class, e -> assertThat(e.maxResults()).isEqualTo(10_000));
    assertThat(pages.served).isEqualTo(10_001);
    assertThat(pages.whoRequests).hasSize(101);
    assertThat(pages.whoRequests.get(100).getPagination().getLimit()).isEqualTo(1);
  }

  @Test
  void whoHasAccessExactlyAtTheDefaultCapSucceeds() {
    Pages pages = new Pages(Permio.DEFAULT_MAX_RESULTS);
    start(pages);
    assertThat(permio.whoHasAccess("/docs/1")).hasSize(Permio.DEFAULT_MAX_RESULTS);
  }

  @Test
  void listAccessOverTheCapStopsAtCapPlusOne() {
    Pages pages = new Pages(1_000_000);
    start(pages);
    assertThatThrownBy(() -> permio.listAccess("alice", "docs.read", "/docs", 120))
        .isInstanceOfSatisfying(
            PermioTooManyResultsException.class,
            e -> {
              assertThat(e.maxResults()).isEqualTo(120);
              assertThat(e.getMessage()).contains("listAccessIterable");
            });
    assertThat(pages.served).isEqualTo(121);
    assertThat(pages.accessRequests.stream().map(r -> r.getPagination().getLimit()))
        .containsExactly(100, 21);
  }

  @Test
  void asyncCollectAllFormsHonourTheCap() {
    start(new Pages(500));
    assertThatThrownBy(() -> permio.whoHasAccessAsync("/docs/1", null, false, 10).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(PermioTooManyResultsException.class);
    assertThatThrownBy(() -> permio.listAccessAsync("alice", "docs.read", "/docs", 10).join())
        .hasCauseInstanceOf(PermioTooManyResultsException.class);
    assertThat(permio.whoHasAccessAsync("/docs/1", null, false, 500).join()).hasSize(500);
  }

  @Test
  void nonPositiveCapIsAValidationError() {
    Pages pages = new Pages(10);
    start(pages);
    assertThatThrownBy(() -> permio.whoHasAccess("/docs/1", null, false, 0))
        .isInstanceOf(PermioValidationException.class)
        .hasMessageContaining("maxResults");
    assertThatThrownBy(() -> permio.listAccess("alice", "docs.read", "/docs", -1))
        .isInstanceOf(PermioValidationException.class);
    assertThat(pages.served).isZero();
    assertThat(server.calls).isEmpty();
  }

  // -- batchCheck bound ---------------------------------------------------------------------------

  private static final class AllowAll extends PermissionsServiceGrpc.PermissionsServiceImplBase {
    final List<BatchCheckRequest> requests = new CopyOnWriteArrayList<>();
    volatile int failOnChunk = -1;

    @Override
    public void batchCheck(BatchCheckRequest request, StreamObserver<BatchCheckResponse> o) {
      int n = requests.size();
      requests.add(request);
      if (n == failOnChunk) {
        o.onError(Status.PERMISSION_DENIED.withDescription("no").asRuntimeException());
        return;
      }
      BatchCheckResponse.Builder res = BatchCheckResponse.newBuilder();
      for (String uri : request.getResourceUrisList()) {
        res.addResults(BatchCheckResult.newBuilder().setResourceUri(uri).setAllowed(true));
      }
      reply(o, res::build);
    }
  }

  private static List<String> uris(int n) {
    return IntStream.range(0, n).mapToObj(i -> "/d/" + i).collect(Collectors.toList());
  }

  @Test
  void batchCheckRejectsMoreThanTheDefaultBound() {
    AllowAll svc = new AllowAll();
    start(svc);
    assertThat(permio.batchCheck("alice", "docs.read", uris(Permio.DEFAULT_MAX_BATCH_RESOURCES)))
        .hasSize(10_000);
    assertThat(svc.requests).hasSize(100);
    svc.requests.clear();
    assertThatThrownBy(
            () ->
                permio.batchCheck(
                    "alice", "docs.read", uris(Permio.DEFAULT_MAX_BATCH_RESOURCES + 1)))
        .isInstanceOf(PermioValidationException.class)
        .hasMessageContaining("at most 10000 resources");
    assertThat(svc.requests).isEmpty();
  }

  @Test
  void batchCheckBoundIsConfigurableAndCountsDuplicates() {
    AllowAll svc = new AllowAll();
    start(svc);
    assertThatThrownBy(
            () -> permio.batchCheck("alice", List.of("docs.read"), List.of("/a", "/a", "/a"), 2))
        .isInstanceOf(PermioValidationException.class);
    assertThatThrownBy(
            () -> permio.batchCheckDetailed("alice", List.of("docs.read"), List.of("/a"), 0))
        .isInstanceOf(PermioValidationException.class);
    assertThatThrownBy(
            () ->
                permio.batchCheckDetailedAsync("alice", List.of("docs.read"), uris(30), 20).join())
        .hasCauseInstanceOf(PermioValidationException.class);
    assertThat(svc.requests).isEmpty();
    assertThat(permio.batchCheck("alice", List.of("docs.read"), uris(20_000), 20_000))
        .hasSize(20_000);
    assertThat(svc.requests).hasSize(200);
  }

  @Test
  void batchCheckStopsAtTheFirstFailingChunk() {
    AllowAll svc = new AllowAll();
    svc.failOnChunk = 1;
    start(svc);
    assertThatThrownBy(() -> permio.batchCheck("alice", "docs.read", uris(5_000)))
        .isInstanceOf(PermioPermissionException.class);
    // At most the four chunks in flight plus the one sent when the first completed.
    assertThat(svc.requests.size()).isLessThanOrEqualTo(Permio.MAX_PARALLEL_CHUNKS + 1);
  }

  // -- list pages: timeout and retries ------------------------------------------------------------

  /** Fails ListPermissions with the queued errors, then answers with one empty page. */
  private static final class FlakyList extends PermissionsServiceGrpc.PermissionsServiceImplBase {
    final Deque<StatusRuntimeException> errors = new ArrayDeque<>();
    final List<Long> times = new CopyOnWriteArrayList<>();
    volatile long sleepMillis;

    @Override
    public void listPermissions(
        ListPermissionsRequest request, StreamObserver<ListPermissionsResponse> o) {
      times.add(System.nanoTime());
      StatusRuntimeException e;
      synchronized (errors) {
        e = errors.poll();
      }
      if (e != null) {
        o.onError(e);
        return;
      }
      sleep(sleepMillis);
      reply(o, ListPermissionsResponse::getDefaultInstance);
    }

    @Override
    public void check(CheckPermissionRequest request, StreamObserver<CheckPermissionResponse> o) {
      sleep(sleepMillis);
      reply(o, () -> CheckPermissionResponse.newBuilder().setPassed(true).build());
    }

    private static void sleep(long millis) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Test
  void listTimeoutAppliesToListPagesOnly() {
    FlakyList svc = new FlakyList();
    svc.sleepMillis = 300;
    start(
        svc,
        b ->
            b.retryPolicy(RetryPolicy.NONE)
                .timeout(Duration.ofSeconds(5))
                .listTimeout(Duration.ofMillis(50)));
    assertThatThrownBy(() -> permio.listPermissions().toList())
        .isInstanceOf(PermioTimeoutException.class)
        .hasMessage("request timed out after 50ms");
    // Checks keep the normal per-attempt timeout.
    assertThat(permio.check("alice", "docs.read", "/docs/1")).isTrue();
  }

  @Test
  void listTimeoutDefaultsToTimeout() {
    FlakyList svc = new FlakyList();
    svc.sleepMillis = 300;
    start(svc, b -> b.retryPolicy(RetryPolicy.NONE).timeout(Duration.ofMillis(50)));
    assertThatThrownBy(() -> permio.listPermissions().toList())
        .isInstanceOf(PermioTimeoutException.class)
        .hasMessage("request timed out after 50ms");
  }

  @Test
  void deadlineExceededOnAListPageBacksOffBeforeRetrying() {
    FlakyList svc = new FlakyList();
    svc.errors.add(Status.DEADLINE_EXCEEDED.asRuntimeException());
    svc.errors.add(Status.DEADLINE_EXCEEDED.asRuntimeException());
    RetryPolicy policy =
        RetryPolicy.builder()
            .maxRetries(2)
            .baseDelay(Duration.ofMillis(80))
            .maxDelay(Duration.ofSeconds(1))
            .build();
    start(svc, b -> b.retryPolicy(policy));
    assertThat(permio.listPermissions().toList()).isEmpty();
    assertThat(svc.times).hasSize(3);
    // Jittered backoff waits at least half the ceiling: 40 ms, then 80 ms.
    assertThat(TimeUnit.NANOSECONDS.toMillis(svc.times.get(1) - svc.times.get(0)))
        .isGreaterThanOrEqualTo(35);
    assertThat(TimeUnit.NANOSECONDS.toMillis(svc.times.get(2) - svc.times.get(1)))
        .isGreaterThanOrEqualTo(75);
  }

  @Test
  void rateLimitedListPageWaitsForRetryInfo() {
    FlakyList svc = new FlakyList();
    svc.errors.add(rateLimited(Duration.ofMillis(200)));
    start(
        svc,
        b ->
            b.retryPolicy(
                RetryPolicy.builder()
                    .maxRetries(2)
                    .baseDelay(Duration.ofMillis(1))
                    .maxDelay(Duration.ofSeconds(1))
                    .build()));
    assertThat(permio.listPermissions().toList()).isEmpty();
    assertThat(svc.times).hasSize(2);
    assertThat(TimeUnit.NANOSECONDS.toMillis(svc.times.get(1) - svc.times.get(0)))
        .isGreaterThanOrEqualTo(190);
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
}
