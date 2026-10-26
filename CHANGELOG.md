# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [0.1.0] - Unreleased

First release of the Java SDK, matching permio-js (including its grant/revoke/whoAmI and
Retry-After work).

### Added
- `Permio` client with a builder: API key (or `PERMIO_API_KEY`) with local format and checksum
  validation, base URL (`DEFAULT_BASE_URL`, `DEV_BASE_URL`), default project, per-attempt timeout,
  retry policy, injectable channel and executor.
- `check`, `checkAll`, `checkAny`, `checkDetailed`, `assertAllowed`, `batchCheck`,
  `batchCheckDetailed`, `listAccess` (`AccessScopes`), `whoHasAccess`, `grant`, `revoke`, `whoAmI`,
  with `CompletableFuture` variants.
- Lazy paginated `listPermissions`, `listRoles`, `listPrincipals`, `listGrants`.
- `Groups`: create, get, update, delete, list, add/remove members, list members, list a
  principal's groups; `Permio.group(slug)` / `Groups.principal(slug)`.
- `Resources.resource(...)` URI builder and `assertResourceUri`.
- Typed unchecked exceptions under `PermioException`, including `PermioRateLimitException` with
  `retryAfter()`.
- Retries with jittered exponential backoff for idempotent reads only; `RetryInfo` /
  `retry-after` honoured on `RESOURCE_EXHAUSTED`.
- Generated gRPC stubs for the public API (PrivateJAR/proto v0.0.18), reachable through
  `permio.raw()`.
