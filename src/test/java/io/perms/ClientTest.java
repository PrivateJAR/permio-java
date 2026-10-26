package io.perms;

import static io.perms.TestServer.reply;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.perms.proto.pagination.v1.PaginationResponse;
import io.perms.proto.permissions.v1.APIKeyAccess;
import io.perms.proto.permissions.v1.AccessGrant;
import io.perms.proto.permissions.v1.AccessGrantDirect;
import io.perms.proto.permissions.v1.AccessGrantMatch;
import io.perms.proto.permissions.v1.AccessGrantRole;
import io.perms.proto.permissions.v1.AccessScope;
import io.perms.proto.permissions.v1.AccessScopeKind;
import io.perms.proto.permissions.v1.BatchCheckRequest;
import io.perms.proto.permissions.v1.BatchCheckResponse;
import io.perms.proto.permissions.v1.BatchCheckResult;
import io.perms.proto.permissions.v1.CheckPermissionRequest;
import io.perms.proto.permissions.v1.CheckPermissionResponse;
import io.perms.proto.permissions.v1.GrantUserPermissionsAndRolesOnResourceRequest;
import io.perms.proto.permissions.v1.ListAccessRequest;
import io.perms.proto.permissions.v1.ListAccessResponse;
import io.perms.proto.permissions.v1.ListPermissionsRequest;
import io.perms.proto.permissions.v1.ListPermissionsResponse;
import io.perms.proto.permissions.v1.ListPrincipalsWithAccessRequest;
import io.perms.proto.permissions.v1.ListPrincipalsWithAccessResponse;
import io.perms.proto.permissions.v1.MissingPermissions;
import io.perms.proto.permissions.v1.Permission;
import io.perms.proto.permissions.v1.PermissionsServiceGrpc;
import io.perms.proto.permissions.v1.PrincipalAccess;
import io.perms.proto.permissions.v1.PrincipalType;
import io.perms.proto.permissions.v1.RevokeUserPermissionsAndRolesOnResourceRequest;
import io.perms.proto.permissions.v1.WhoAmIRequest;
import io.perms.proto.permissions.v1.WhoAmIResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClientTest {
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

  private Permio start(PermissionsServiceGrpc.PermissionsServiceImplBase service) {
    server = new TestServer(service);
    permio = server.client();
    return permio;
  }

  /** A Check fake that records requests and answers from a function. */
  private static final class CheckService
      extends PermissionsServiceGrpc.PermissionsServiceImplBase {
    final List<CheckPermissionRequest> requests = new CopyOnWriteArrayList<>();
    final java.util.function.Function<CheckPermissionRequest, CheckPermissionResponse> answer;

    CheckService(java.util.function.Function<CheckPermissionRequest, CheckPermissionResponse> a) {
      this.answer = a;
    }

    @Override
    public void check(
        CheckPermissionRequest request, StreamObserver<CheckPermissionResponse> observer) {
      requests.add(request);
      reply(observer, () -> answer.apply(request));
    }
  }

  private static CheckPermissionResponse allowed() {
    return CheckPermissionResponse.newBuilder().setPassed(true).build();
  }

  private static CheckPermissionResponse missing(String resource, String... permissions) {
    return CheckPermissionResponse.newBuilder()
        .setPassed(false)
        .addMissingPermissions(
            MissingPermissions.newBuilder()
                .setResourceUri(resource)
                .addAllMissingPermissions(List.of(permissions)))
        .build();
  }

  @Test
  void checkSendsTheRequestWithAuthAndProject() {
    CheckService svc = new CheckService(r -> allowed());
    start(svc);
    assertThat(permio.check("alice", "docs.read", "/docs/1")).isTrue();
    CheckPermissionRequest req = svc.requests.get(0);
    assertThat(req.getProjectName()).isEqualTo("my-app");
    assertThat(req.getPrincipalId()).isEqualTo("alice");
    assertThat(req.getPermissionsList()).containsExactly("docs.read");
    assertThat(req.getResourceUrisList()).containsExactly("/docs/1");
    assertThat(server.calls.get(0).authorization()).isEqualTo("Bearer " + TestServer.VALID_KEY);
  }

  @Test
  void checkReturnsFalseWhenDenied() {
    start(new CheckService(r -> missing("/docs/1", "docs.read")));
    assertThat(permio.check("alice", "docs.read", "/docs/1")).isFalse();
    CheckResult result = permio.checkDetailed("alice", "docs.read", "/docs/1");
    assertThat(result.allowed()).isFalse();
    assertThat(result.missing())
        .containsExactly(new MissingPermission("/docs/1", List.of("docs.read")));
  }

  @Test
  void withProjectOverridesTheDefault() {
    CheckService svc = new CheckService(r -> allowed());
    start(svc);
    permio.withProject("other").check("alice", "docs.read", "/docs/1");
    assertThat(svc.requests.get(0).getProjectName()).isEqualTo("other");
    // Closing a view leaves the client usable.
    permio.withProject("other").close();
    assertThat(permio.check("alice", "docs.read", "/docs/1")).isTrue();
  }

  @Test
  void noProjectIsAConfigError() {
    CheckService svc = new CheckService(r -> allowed());
    server = new TestServer(svc);
    permio = server.client(b -> b.project(null));
    assertThatThrownBy(() -> permio.check("alice", "docs.read", "/docs/1"))
        .isInstanceOf(PermioConfigException.class)
        .hasMessageContaining("no project");
    assertThat(server.calls).isEmpty();
  }

  @Test
  void validatesInputBeforeSending() {
    CheckService svc = new CheckService(r -> allowed());
    start(svc);
    assertThatThrownBy(() -> permio.check("", "docs.read", "/docs/1"))
        .isInstanceOf(PermioValidationException.class);
    assertThatThrownBy(() -> permio.check("alice", "docs.read", "docs/1"))
        .isInstanceOf(PermioValidationException.class);
    assertThatThrownBy(() -> permio.check("alice", null, "/docs/1"))
        .isInstanceOf(PermioValidationException.class);
    List<String> many = IntStream.range(0, 101).mapToObj(i -> "p" + i).collect(Collectors.toList());
    assertThatThrownBy(() -> permio.checkAll("alice", many, "/docs/1"))
        .isInstanceOf(PermioValidationException.class)
        .hasMessageContaining("at most 100");
    assertThat(server.calls).isEmpty();
  }

  @Test
  void checkAllDeduplicates() {
    CheckService svc = new CheckService(r -> allowed());
    start(svc);
    assertThat(permio.checkAll("alice", List.of("a", "b", "a"), List.of("/x", "/y", "/x")))
        .isTrue();
    assertThat(svc.requests.get(0).getPermissionsList()).containsExactly("a", "b");
    assertThat(svc.requests.get(0).getResourceUrisList()).containsExactly("/x", "/y");
  }

  @Test
  void checkAnyNeedsOnePermissionPerResource() {
    // Holds "a" on /x but nothing on /y.
    start(
        new CheckService(
            r -> {
              CheckPermissionResponse.Builder res = CheckPermissionResponse.newBuilder();
              if (r.getResourceUrisList().contains("/x")) {
                res.addMissingPermissions(
                    MissingPermissions.newBuilder()
                        .setResourceUri("/x")
                        .addMissingPermissions("b"));
              }
              if (r.getResourceUrisList().contains("/y")) {
                res.addMissingPermissions(
                    MissingPermissions.newBuilder()
                        .setResourceUri("/y")
                        .addMissingPermissions("a")
                        .addMissingPermissions("b"));
              }
              return res.build();
            }));
    assertThat(permio.checkAny("alice", List.of("a", "b"), "/x")).isTrue();
    assertThat(permio.checkAny("alice", List.of("a", "b"), List.of("/x", "/y"))).isFalse();
    assertThat(permio.checkAnyAsync("alice", List.of("a", "b"), "/x").join()).isTrue();
    assertThat(permio.checkAllAsync("alice", List.of("a", "b"), "/x").join()).isFalse();
  }

  @Test
  void checkAnyFailsClosedOnADenialWithoutDetails() {
    start(new CheckService(r -> CheckPermissionResponse.newBuilder().setPassed(false).build()));
    assertThat(permio.checkAny("alice", List.of("a", "b"), "/x")).isFalse();
    assertThat(permio.checkAny("alice", List.of("a"), List.of("/x", "/y"))).isFalse();
  }

  @Test
  void defaultAsyncExecutorIsBounded() throws Exception {
    java.util.concurrent.atomic.AtomicInteger inFlight =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger maxInFlight =
        new java.util.concurrent.atomic.AtomicInteger();
    start(
        new CheckService(
            r -> {
              maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
              try {
                Thread.sleep(50);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              inFlight.decrementAndGet();
              return allowed();
            }));
    List<java.util.concurrent.CompletableFuture<Boolean>> futures = new ArrayList<>();
    for (int i = 0; i < 3 * Permio.DEFAULT_ASYNC_THREADS; i++) {
      futures.add(permio.checkAsync("alice", "docs.read", "/docs/" + i));
    }
    for (java.util.concurrent.CompletableFuture<Boolean> f : futures) {
      assertThat(f.get(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
    assertThat(maxInFlight.get()).isBetween(1, Permio.DEFAULT_ASYNC_THREADS);
  }

  @Test
  void asyncMethodsUseACallerSuppliedExecutor() {
    List<Runnable> ran = new CopyOnWriteArrayList<>();
    server = new TestServer(new CheckService(r -> allowed()));
    permio =
        server.client(
            b ->
                b.executor(
                    task -> {
                      ran.add(task);
                      task.run();
                    }));
    assertThat(permio.checkAllAsync("alice", List.of("a"), List.of("/x")).join()).isTrue();
    permio.assertAllowedAsync("alice", "a", "/x").join();
    assertThat(ran).hasSize(2);
  }

  @Test
  void assertAllowedThrowsDenied() {
    start(new CheckService(r -> missing("/docs/1", "docs.read")));
    assertThatThrownBy(() -> permio.assertAllowed("alice", "docs.read", "/docs/1"))
        .isInstanceOf(PermioDeniedException.class)
        .hasMessage("principal \"alice\" is missing docs.read on /docs/1");
  }

  @Test
  void mapsApiErrors() {
    start(
        new CheckService(
            r -> {
              throw Status.PERMISSION_DENIED
                  .withDescription(
                      "missing permissions: [{ResourceURI:/organisation/o/project/p/docs/1"
                          + " MissingPermissions:[permission.check]}]")
                  .asRuntimeException();
            }));
    assertThatThrownBy(() -> permio.check("alice", "docs.read", "/docs/1"))
        .isInstanceOfSatisfying(
            PermioPermissionException.class,
            e ->
                assertThat(e.missing())
                    .containsExactly(
                        new MissingPermission(
                            "/organisation/o/project/p/docs/1", List.of("permission.check"))));
  }

  @Test
  void checkAsyncCompletesAndFails() {
    start(
        new CheckService(
            r -> {
              if (r.getPrincipalId().equals("bad")) {
                throw Status.UNAUTHENTICATED.asRuntimeException();
              }
              return allowed();
            }));
    assertThat(permio.checkAsync("alice", "docs.read", "/docs/1").join()).isTrue();
    assertThatThrownBy(() -> permio.checkAsync("bad", "docs.read", "/docs/1").join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(PermioAuthException.class);
  }

  // -- batchCheck ---------------------------------------------------------------------------------

  private static final class BatchService
      extends PermissionsServiceGrpc.PermissionsServiceImplBase {
    final List<BatchCheckRequest> requests = new CopyOnWriteArrayList<>();
    boolean dropLast;

    @Override
    public void batchCheck(BatchCheckRequest request, StreamObserver<BatchCheckResponse> observer) {
      requests.add(request);
      BatchCheckResponse.Builder res = BatchCheckResponse.newBuilder();
      List<String> uris = new ArrayList<>(request.getResourceUrisList());
      if (dropLast) {
        uris.remove(uris.size() - 1);
      }
      // Answer in reverse order: the SDK must restore request order.
      for (int i = uris.size() - 1; i >= 0; i--) {
        String uri = uris.get(i);
        boolean ok = uri.endsWith("/ok") || uri.matches(".*/\\d*[02468]");
        res.addResults(
            BatchCheckResult.newBuilder()
                .setResourceUri(uri)
                .setAllowed(ok)
                .addAllMissingPermissions(ok ? List.of() : request.getPermissionsList()));
      }
      reply(observer, res::build);
    }
  }

  @Test
  void batchCheckReturnsRequestOrder() {
    BatchService svc = new BatchService();
    start(svc);
    Map<String, Boolean> result =
        permio.batchCheck("alice", "docs.read", List.of("/a/ok", "/b/no", "/a/ok", "/c/ok"));
    assertThat(new ArrayList<>(result.entrySet()))
        .containsExactly(
            Map.entry("/a/ok", true), Map.entry("/b/no", false), Map.entry("/c/ok", true));
    List<BatchCheckEntry> detailed =
        permio.batchCheckDetailed("alice", List.of("x", "y"), List.of("/b/no"));
    assertThat(detailed.get(0).missing()).containsExactly("x", "y");
  }

  @Test
  void batchCheckSplitsIntoChunksOf100() {
    BatchService svc = new BatchService();
    start(svc);
    List<String> uris =
        IntStream.range(0, 250).mapToObj(i -> "/d/" + i).collect(Collectors.toList());
    Map<String, Boolean> result = permio.batchCheck("alice", "docs.read", uris);
    assertThat(svc.requests).hasSize(3);
    assertThat(svc.requests.stream().mapToInt(BatchCheckRequest::getResourceUrisCount).sum())
        .isEqualTo(250);
    assertThat(new ArrayList<>(result.keySet())).isEqualTo(uris);
    assertThat(result.get("/d/2")).isTrue();
    assertThat(result.get("/d/3")).isFalse();
  }

  @Test
  void batchCheckSendsAtMostFourChunksAtOnce() {
    java.util.concurrent.atomic.AtomicInteger inFlight =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger maxInFlight =
        new java.util.concurrent.atomic.AtomicInteger();
    BatchService inner = new BatchService();
    start(
        new PermissionsServiceGrpc.PermissionsServiceImplBase() {
          @Override
          public void batchCheck(BatchCheckRequest r, StreamObserver<BatchCheckResponse> o) {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
              Thread.sleep(30);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            inner.batchCheck(r, o);
          }
        });
    List<String> uris =
        IntStream.range(0, 1000).mapToObj(i -> "/d/" + i).collect(Collectors.toList());
    List<BatchCheckEntry> result =
        permio.batchCheckDetailedAsync("alice", List.of("docs.read"), uris).join();
    assertThat(result).hasSize(1000);
    assertThat(inner.requests).hasSize(10);
    assertThat(maxInFlight.get()).isBetween(2, Permio.MAX_PARALLEL_CHUNKS);
  }

  @Test
  void batchCheckFailsClosedOnAMissingAnswer() {
    BatchService svc = new BatchService();
    svc.dropLast = true;
    start(svc);
    assertThatThrownBy(() -> permio.batchCheck("alice", "docs.read", List.of("/a/ok", "/b/ok")))
        .isExactlyInstanceOf(PermioException.class)
        .hasMessageContaining("inconsistent response");
  }

  // -- listAccess / whoHasAccess
  // --------------------------------------------------------------------

  @Test
  void listAccessFollowsEveryPage() {
    List<ListAccessRequest> requests = new CopyOnWriteArrayList<>();
    start(
        new PermissionsServiceGrpc.PermissionsServiceImplBase() {
          @Override
          public void listAccess(ListAccessRequest request, StreamObserver<ListAccessResponse> o) {
            requests.add(request);
            boolean first = !request.getPagination().hasCursor();
            reply(
                o,
                () ->
                    ListAccessResponse.newBuilder()
                        .addScopes(
                            AccessScope.newBuilder()
                                .setUri(first ? "/docs/1" : "/docs/_/x")
                                .setScope(
                                    first
                                        ? AccessScopeKind.ACCESS_SCOPE_KIND_SUBTREE
                                        : AccessScopeKind.ACCESS_SCOPE_KIND_PATTERN))
                        .setPagination(
                            PaginationResponse.newBuilder().setNextCursor(first ? "c1" : ""))
                        .build());
          }
        });
    AccessScopes access = permio.listAccess("alice", "docs.read", "/docs");
    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).getPagination().getLimit()).isEqualTo(100);
    assertThat(requests.get(1).getPagination().getCursor()).isEqualTo("c1");
    assertThat(requests.get(0).getUnderUri()).isEqualTo("/docs");
    assertThat(access.coversAll()).isFalse();
    assertThat(access.toPrefixes()).containsExactly("/docs/1");
    assertThat(access.patterns()).containsExactly("/docs/_/x");
    assertThat(access.allows("/docs/1/a")).isTrue();
    assertThat(access.allows("/docs/9/x")).isTrue();
    assertThat(access.allows("/docs/2")).isFalse();
  }

  @Test
  void whoHasAccessMapsEntries() {
    List<ListPrincipalsWithAccessRequest> requests = new CopyOnWriteArrayList<>();
    start(
        new PermissionsServiceGrpc.PermissionsServiceImplBase() {
          @Override
          public void listPrincipalsWithAccess(
              ListPrincipalsWithAccessRequest request,
              StreamObserver<ListPrincipalsWithAccessResponse> o) {
            requests.add(request);
            reply(
                o,
                () ->
                    ListPrincipalsWithAccessResponse.newBuilder()
                        .addPrincipals(
                            PrincipalAccess.newBuilder()
                                .setPrincipalId("alice")
                                .addVia(
                                    AccessGrant.newBuilder()
                                        .setResourceUri("/docs")
                                        .setInherited(true)
                                        .setMatch(AccessGrantMatch.ACCESS_GRANT_MATCH_ANCESTOR)
                                        .setRole(
                                            AccessGrantRole.newBuilder()
                                                .setId("r1")
                                                .setName("Editor"))))
                        .addPrincipals(
                            PrincipalAccess.newBuilder()
                                .setPrincipalId("bob")
                                .setViaGroup("editors")
                                .addVia(
                                    AccessGrant.newBuilder()
                                        .setResourceUri("/docs/1")
                                        .setMatch(AccessGrantMatch.ACCESS_GRANT_MATCH_EXACT)
                                        .setDirect(AccessGrantDirect.getDefaultInstance())))
                        .addPrincipals(
                            PrincipalAccess.newBuilder()
                                .setPrincipalId("group:editors")
                                .setIsGroup(true))
                        .build());
          }
        });
    List<AccessEntry> entries = permio.whoHasAccess("/docs/1");
    assertThat(requests.get(0).hasPermission()).isFalse();
    assertThat(requests.get(0).getExpandGroups()).isFalse();
    assertThat(entries).hasSize(3);
    AccessGrantInfo viaRole = entries.get(0).via().get(0);
    assertThat(viaRole.source()).isEqualTo(AccessGrantInfo.SourceType.ROLE);
    assertThat(viaRole.roleId()).contains("r1");
    assertThat(viaRole.roleName()).contains("Editor");
    assertThat(viaRole.match()).isEqualTo(AccessGrantInfo.Match.ANCESTOR);
    assertThat(viaRole.inherited()).isTrue();
    assertThat(entries.get(0).viaGroup()).isEmpty();
    assertThat(entries.get(1).viaGroup()).contains("editors");
    assertThat(entries.get(1).via().get(0).source()).isEqualTo(AccessGrantInfo.SourceType.DIRECT);
    assertThat(entries.get(2).isGroup()).isTrue();

    permio.whoHasAccess("/docs/1", "docs.read", true);
    assertThat(requests.get(1).getPermission()).isEqualTo("docs.read");
    assertThat(requests.get(1).getExpandGroups()).isTrue();
  }

  // -- pagination ---------------------------------------------------------------------------------

  private static final class PermissionPages
      extends PermissionsServiceGrpc.PermissionsServiceImplBase {
    final List<ListPermissionsRequest> requests = new CopyOnWriteArrayList<>();
    final List<String> cursors;

    PermissionPages(String... nextCursors) {
      this.cursors = List.of(nextCursors);
    }

    @Override
    public void listPermissions(
        ListPermissionsRequest request, StreamObserver<ListPermissionsResponse> o) {
      int page = requests.size();
      requests.add(request);
      reply(
          o,
          () ->
              ListPermissionsResponse.newBuilder()
                  .addPermissions(Permission.newBuilder().setName("p" + page + "a"))
                  .addPermissions(Permission.newBuilder().setName("p" + page + "b"))
                  .setPagination(
                      PaginationResponse.newBuilder()
                          .setNextCursor(page < cursors.size() ? cursors.get(page) : ""))
                  .build());
    }
  }

  @Test
  void listPermissionsIsLazyAndFollowsCursors() {
    PermissionPages svc = new PermissionPages("c1", "c2", "");
    start(svc);
    PagedIterable<Permission> all =
        permio.listPermissions(ListOptions.defaults().withSearch("p").withPageSize(2));
    assertThat(svc.requests).isEmpty();
    List<String> names = all.stream().map(Permission::getName).collect(Collectors.toList());
    assertThat(names).containsExactly("p0a", "p0b", "p1a", "p1b", "p2a", "p2b");
    assertThat(svc.requests).hasSize(3);
    assertThat(svc.requests.get(0).getSearch()).isEqualTo("p");
    assertThat(svc.requests.get(0).getPagination().getLimit()).isEqualTo(2);
    assertThat(svc.requests.get(0).getPagination().hasCursor()).isFalse();
    assertThat(svc.requests.get(2).getPagination().getCursor()).isEqualTo("c2");
    // Iterating again starts from the first page, and fetches only what is consumed.
    all.iterator().next();
    assertThat(svc.requests).hasSize(4);
    assertThat(svc.requests.get(3).getPagination().hasCursor()).isFalse();
  }

  @Test
  void aRepeatedCursorIsAnError() {
    PermissionPages svc = new PermissionPages("same", "same", "never");
    start(svc);
    assertThatThrownBy(() -> permio.listPermissions().toList())
        .isInstanceOf(PermioException.class)
        .hasMessageContaining("cursor repeated");
    assertThat(svc.requests).hasSize(2);
  }

  @Test
  void searchIsRejectedWhereTheRpcCannotSearch() {
    start(new PermissionsServiceGrpc.PermissionsServiceImplBase() {});
    ListOptions search = ListOptions.defaults().withSearch("x");
    assertThatThrownBy(() -> permio.listGrants("alice", search))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("listGrants");
    assertThatThrownBy(() -> permio.groups().listMembers("editors", search))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> permio.groups().listForPrincipal("alice", search))
        .isInstanceOf(IllegalArgumentException.class);
    // Page size alone is fine.
    permio.listGrants("alice", ListOptions.defaults().withPageSize(5));
    assertThat(server.calls).isEmpty();
  }

  @Test
  void listErrorsArePermioExceptions() {
    start(
        new PermissionsServiceGrpc.PermissionsServiceImplBase() {
          @Override
          public void listPermissions(
              ListPermissionsRequest r, StreamObserver<ListPermissionsResponse> o) {
            o.onError(Status.NOT_FOUND.asRuntimeException());
          }
        });
    assertThatThrownBy(() -> permio.listPermissions().toList())
        .isInstanceOf(PermioNotFoundException.class);
  }

  // -- grant / revoke -----------------------------------------------------------------------------

  @Test
  void grantAndRevokeSendTheirRequests() {
    List<GrantUserPermissionsAndRolesOnResourceRequest> grants = new CopyOnWriteArrayList<>();
    List<RevokeUserPermissionsAndRolesOnResourceRequest> revokes = new CopyOnWriteArrayList<>();
    start(
        new PermissionsServiceGrpc.PermissionsServiceImplBase() {
          @Override
          public void grantUserPermissionsAndRolesOnResource(
              GrantUserPermissionsAndRolesOnResourceRequest r, StreamObserver<Empty> o) {
            grants.add(r);
            reply(o, Empty::getDefaultInstance);
          }

          @Override
          public void revokeUserPermissionsAndRolesOnResource(
              RevokeUserPermissionsAndRolesOnResourceRequest r, StreamObserver<Empty> o) {
            revokes.add(r);
            reply(o, Empty::getDefaultInstance);
          }
        });
    permio.grant(
        Grant.to(Permio.group("editors"))
            .on("/docs")
            .permissions("docs.read", "docs.read")
            .roles("r1"));
    permio.revoke("alice", "docs.read", "/docs");
    permio.grantAsync(Grant.to("bob").on("/x").roles("r2")).join();

    assertThat(grants.get(0).getUserId()).isEqualTo("group:editors");
    assertThat(grants.get(0).getResourceUri()).isEqualTo("/docs");
    assertThat(grants.get(0).getPermissionsList()).containsExactly("docs.read");
    assertThat(grants.get(0).getRolesList()).containsExactly("r1");
    assertThat(grants.get(0).getProjectName()).isEqualTo("my-app");
    assertThat(revokes.get(0).getUserId()).isEqualTo("alice");
    assertThat(revokes.get(0).getPermissionsList()).containsExactly("docs.read");
    assertThat(grants.get(1).getRolesList()).containsExactly("r2");
  }

  @Test
  void grantNeedsSomethingToGrant() {
    start(new PermissionsServiceGrpc.PermissionsServiceImplBase() {});
    assertThatThrownBy(() -> permio.grant(Grant.to("alice").on("/docs")))
        .isInstanceOf(PermioValidationException.class)
        .hasMessageContaining("at least one permission or role");
    assertThatThrownBy(() -> permio.grant(Grant.to("alice").permissions("x")))
        .isInstanceOf(PermioValidationException.class);
    assertThatThrownBy(() -> permio.grant(Grant.to("alice").on("/d").permissions("")))
        .isInstanceOf(PermioValidationException.class);
    assertThat(server.calls).isEmpty();
  }

  // -- whoAmI -------------------------------------------------------------------------------------

  @Test
  void whoAmIDescribesTheKey() {
    start(
        new PermissionsServiceGrpc.PermissionsServiceImplBase() {
          @Override
          public void whoAmI(WhoAmIRequest r, StreamObserver<WhoAmIResponse> o) {
            reply(
                o,
                () ->
                    WhoAmIResponse.newBuilder()
                        .setPrincipalId("key-1")
                        .setOrganisationId("org-1")
                        .setPrincipalType(PrincipalType.PRINCIPAL_TYPE_API_KEY)
                        .setProjectName("my-app")
                        .setApiKeyAccess(APIKeyAccess.API_KEY_ACCESS_FULL)
                        .build());
          }
        });
    WhoAmI me = permio.whoAmIAsync().join();
    assertThat(me.principalId()).isEqualTo("key-1");
    assertThat(me.organisationId()).isEqualTo("org-1");
    assertThat(me.kind()).isEqualTo(WhoAmI.PrincipalKind.API_KEY);
    assertThat(me.project()).contains("my-app");
    assertThat(me.apiKeyAccess()).contains(WhoAmI.ApiKeyAccess.FULL);
    assertThat(me.email()).isEmpty();
  }

  @Test
  void whoAmIForAUserHasNoKeyAccess() {
    start(
        new PermissionsServiceGrpc.PermissionsServiceImplBase() {
          @Override
          public void whoAmI(WhoAmIRequest r, StreamObserver<WhoAmIResponse> o) {
            reply(
                o,
                () ->
                    WhoAmIResponse.newBuilder()
                        .setPrincipalId("u1")
                        .setPrincipalType(PrincipalType.PRINCIPAL_TYPE_USER)
                        .setEmail("a@example.com")
                        .build());
          }
        });
    WhoAmI me = permio.whoAmI();
    assertThat(me.kind()).isEqualTo(WhoAmI.PrincipalKind.USER);
    assertThat(me.apiKeyAccess()).isEmpty();
    assertThat(me.project()).isEmpty();
    assertThat(me.email()).contains("a@example.com");
  }

  // -- builder ------------------------------------------------------------------------------------

  @Test
  void builderValidatesConfiguration() {
    assertThatThrownBy(() -> Permio.builder().apiKey("perms_nope").build())
        .isInstanceOf(PermioConfigException.class);
    for (String bad :
        new String[] {"ftp://api.perms.io", "api.perms.io", "https://api.perms.io/v1", ""}) {
      assertThatThrownBy(() -> Permio.builder().apiKey(TestServer.VALID_KEY).baseUrl(bad).build())
          .as(bad)
          .isInstanceOf(PermioConfigException.class);
    }
    try (Permio p = Permio.builder().apiKey(TestServer.VALID_KEY).build()) {
      assertThat(p.baseUrl()).isEqualTo(Permio.DEFAULT_BASE_URL);
      assertThat(p.apiKeyKind()).isEqualTo(ApiKeys.Kind.OPAQUE);
      assertThat(p.project()).isEmpty();
    }
    try (Permio p =
        Permio.builder()
            .apiKey(TestServer.JWT_KEY)
            .baseUrl("http://localhost:8080/")
            .project("x")
            .build()) {
      assertThat(p.baseUrl()).isEqualTo("http://localhost:8080");
      assertThat(p.apiKeyKind()).isEqualTo(ApiKeys.Kind.JWT);
      assertThat(p.project()).contains("x");
    }
    assertThat(Permio.VERSION).isNotEqualTo("unknown");
  }
}
