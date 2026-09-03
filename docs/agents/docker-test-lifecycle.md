# Docker test resource lifecycle

Database-backed and Docker-backed tests must use fresh, isolated infrastructure created specifically for the validation run. A test must never run against an already-existing developer database, PostgreSQL container, Compose stack, Docker container, volume, or network.

The point is both correctness and safety: pre-existing state can make tests false-green/false-red, and teardown must never risk deleting a developer resource that the test did not create.

## Scope

This rule applies to tests, reviews, benchmarks, migration checks, E2E runs, load validations, and any other temporary verification that needs a database or Docker infrastructure.

- If the validation needs PostgreSQL, provision a fresh database service/container for that run rather than pointing at an already-running local database.
- If the validation needs Docker, create fresh containers and test-owned storage/networking for that run.
- CI service containers and Testcontainers are acceptable when they are created fresh per run and automatically destroyed afterward.
- Reusable Testcontainers and long-lived local Docker services are not acceptable test infrastructure.

This rule does not authorize deleting any developer service, database, container, volume, or network that existed before the verification began. Those resources must remain untouched and must not be used as the test target.

## Isolate before creating

Prefer a unique Compose project name or explicit labels/names so the complete lifecycle is owned by a single validation run. Test storage must also be fresh; do not attach the temporary stack to a volume that predates the run.

Example:

```bash
project="iwrite-test-$$"
cleanup() {
  docker compose -p "$project" -f docker-compose.yml down -v --remove-orphans
}
trap cleanup EXIT INT TERM

docker compose -p "$project" -f docker-compose.yml up -d --wait db
# run the validation against this fresh database only
```

Register the trap or equivalent `finally`/framework teardown before `up` so failures still clean the resources.

If a Compose file declares fixed/external volume or container names that would cause reuse, use a test override or another isolated setup instead. A unique Compose project name is not sufficient if the configuration still points at shared storage.

## Direct Docker resources

For one-off containers, prefer `docker run --rm`. If the test creates a named or anonymous volume outside Compose, capture its exact name/ID and remove it explicitly in teardown.

Do not rely on `docker system prune`, `docker volume prune`, or other broad cleanup commands: they can delete unrelated developer resources and do not prove that the test owns what it removes.

## Framework-managed containers

When a framework such as Testcontainers owns the lifecycle, keep its cleanup/reaper behavior enabled and do not opt into reusable containers for test-only infrastructure. Each validation run must receive a fresh container/database state. If custom lifecycle code creates extra containers or volumes, that code is responsible for their teardown too.

## Completion check

A database/Docker-backed verification is not complete until:

1. fresh isolated infrastructure was created for that run;
2. the test result is known;
3. teardown has run even on the failure path where practical;
4. no container or volume created by that verification remains.

Use the Compose project, labels, or captured resource names/IDs to verify cleanup. Pre-existing developer resources must neither be used by the test nor touched by teardown.
