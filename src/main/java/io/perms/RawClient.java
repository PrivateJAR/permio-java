package io.perms;

import io.grpc.Channel;
import io.grpc.stub.AbstractStub;
import io.perms.proto.organisation.v1.OrganisationServiceGrpc;
import io.perms.proto.permissions.v1.PermissionsServiceGrpc;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * The generated gRPC stubs, as an escape hatch for RPCs the SDK does not wrap (permission and role
 * CRUD, organisations, projects, API keys, ...). Get it with {@link Permio#raw()}.
 *
 * <p>The stubs run over the client's channel, so auth, per-attempt timeouts and retries for
 * idempotent reads apply. Request messages need {@code project_name} set explicitly. Errors are
 * plain {@code StatusRuntimeException}s; convert them with {@link PermioException#from}.
 *
 * <pre>{@code
 * permio.raw().permissions().createPermission(
 *     CreatePermissionRequest.newBuilder().setProjectName("my-app").setName("docs.read").build());
 * }</pre>
 */
public final class RawClient {
  private final Channel channel;
  private final Duration callTimeout;

  RawClient(Channel channel, Duration callTimeout) {
    this.channel = channel;
    this.callTimeout = callTimeout;
  }

  /** The client's channel, with auth, timeouts and retries applied, for building any stub. */
  public Channel channel() {
    return channel;
  }

  /** Blocking {@code PermissionsService} stub. */
  public PermissionsServiceGrpc.PermissionsServiceBlockingStub permissions() {
    return withTimeout(PermissionsServiceGrpc.newBlockingStub(channel));
  }

  /** {@code ListenableFuture}-based {@code PermissionsService} stub. */
  public PermissionsServiceGrpc.PermissionsServiceFutureStub permissionsFuture() {
    return withTimeout(PermissionsServiceGrpc.newFutureStub(channel));
  }

  /** {@code StreamObserver}-based {@code PermissionsService} stub. */
  public PermissionsServiceGrpc.PermissionsServiceStub permissionsAsync() {
    return withTimeout(PermissionsServiceGrpc.newStub(channel));
  }

  /** Blocking {@code OrganisationService} stub. */
  public OrganisationServiceGrpc.OrganisationServiceBlockingStub organisations() {
    return withTimeout(OrganisationServiceGrpc.newBlockingStub(channel));
  }

  /** {@code ListenableFuture}-based {@code OrganisationService} stub. */
  public OrganisationServiceGrpc.OrganisationServiceFutureStub organisationsFuture() {
    return withTimeout(OrganisationServiceGrpc.newFutureStub(channel));
  }

  /** {@code StreamObserver}-based {@code OrganisationService} stub. */
  public OrganisationServiceGrpc.OrganisationServiceStub organisationsAsync() {
    return withTimeout(OrganisationServiceGrpc.newStub(channel));
  }

  private <S extends AbstractStub<S>> S withTimeout(S stub) {
    return callTimeout == null
        ? stub
        : stub.withDeadlineAfter(callTimeout.toNanos(), TimeUnit.NANOSECONDS);
  }
}
