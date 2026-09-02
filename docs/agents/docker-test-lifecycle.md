# Docker test resource lifecycle

Docker resources created only to run IWrite tests, reviews, benchmarks, migration checks, or other temporary validation are disposable test infrastructure. They must not remain on the developer machine after that validation finishes.

## Scope

This rule applies to containers, named or anonymous volumes, and compose-created networks that the verification itself creates. It does not authorize deleting a developer service or volume that already existed before the verification began.

## Isolate before creating

Prefer a unique Compose project name or explicit labels/names so cleanup can target exactly the resources created by the test. Do not run a temporary test stack in the same Compose project as a long-lived developer stack if teardown would remove shared state.

Example:

```bash
project="iwrite-test-$$"
cleanup() {
  docker compose -p "$project" -f docker-compose.yml down -v --remove-orphans
}
trap cleanup EXIT INT TERM

docker compose -p "$project" -f docker-compose.yml up -d db
# run the validation
```

Register the trap or equivalent `finally`/framework teardown before `up` so failures still clean the resources.

## Direct Docker resources

For one-off containers, prefer `docker run --rm`. If the test creates a named or anonymous volume outside Compose, capture its exact name/ID and remove it explicitly in teardown.

Do not rely on `docker system prune`, `docker volume prune`, or other broad cleanup commands: they can delete unrelated developer resources and do not prove that the test owns what it removes.

## Framework-managed containers

When a framework such as Testcontainers owns the lifecycle, keep its cleanup/reaper behavior enabled and do not opt into reusable containers for test-only infrastructure. If custom lifecycle code creates extra containers or volumes, that code is responsible for their teardown too.

## Completion check

A Docker-backed verification is not complete until:

1. the test result is known;
2. teardown has run even on the failure path where practical;
3. no container or volume created by that verification remains.

Use the Compose project, labels, or captured resource names/IDs to verify cleanup. Reused pre-existing developer resources are outside this teardown and must remain untouched.
