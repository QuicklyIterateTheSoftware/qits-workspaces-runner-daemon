# qits-workspaces-runner-daemon — working notes

Read `README.md` first: it is the operator's view of what the runner does on a node. This file is
the working conventions on top of it. It deliberately mirrors qits-ci-runner-daemon's `AGENTS.md`
and qits-runner-javalib's; where this repository differs, it says why.

## The rules that shape everything

**The runner is qits-runner-javalib's, embedded.** Registration, the bearer, the control socket,
the slot ledger, self-update, decommission, the heartbeat, housekeeping, the runner's own log
shipping and the install-script template are `eu.wohlben.qits:qits-runner-toolkit` (qits-771). This
repository supplies exactly what the toolkit asks an embedder for: a `RunnerIdentity`
(`WorkspacesRunnerIdentity`), a sealed frame set with its `DomainCodec` (the protocol module), a
`RunnerDomain` (`WorkspacesRunnerDomain`) and a `main` (`Main`). **Never fork or copy a toolkit
class.** A case the toolkit's public API cannot express is a gap fixed there, released, and pinned
here.

**A clone of this repo alone builds and tests green** — given the platform's maven repository,
which the toolkit, `qits-runner-protocol` and `qits-containers-driver` are published to. No docker,
no prior `mvn install` elsewhere, no credentials. `./mvnw verify` is the gate.

**It compiles to a fully static musl GraalVM native image**, with qits-ci-runner-daemon's
toolchain (`docker/Dockerfile.musl-builder`, a copy) and for its reason: a runner node is whatever
Linux a person had. No Jackson, no REST client, no docker SDK; every docker call goes through the
toolkit's process seam (`RunnerDocker`, `qits-containers-driver`), under a deadline.

**A healthy runner never exits**, and **a rollover never drains.** Workspace containers are node
state that outlives the runner process: `WorkspacesRunnerDomain.quiescent()` answers "no take,
stop or delete in flight", never "holds nothing", so the successor starts at once and adopts the
running containers. Keep it that way — a quiescence that waited for workspaces to end would never
hand over.

## Module conventions

`eu.wohlben.qits.workspacesrunner.protocol` (the published jar `qits-workspaces-runner-protocol`)
and `eu.wohlben.qits.workspacesrunner` (the binary), one package per module.

**The protocol module depends on `qits-runner-protocol` and nothing else**, enforced by
`maven-enforcer-plugin`. qits-workspaces-service (Jackson) and the native runner (Vert.x) both link
it. Plain records, plain constructors, no annotations; fields are read through `WireMaps`.

**`Main` is the only CDI bean.** Everything below it is a plain class taking its collaborators as
constructor arguments; nothing reads configuration — the toolkit's `RunnerSettings` parses the
environment.

**Every name on a node is derived**, from the identity (`RunnerIdentity.nodeName`/`nodeVolume`,
`OwnResources.labels`) and `WorkspaceNames` — never from a frame. A workspace's container and its
`/workspace` volume are keyed on the workspace ROW id.

## Testing

Plain JUnit 5. **No Mockito, no `@QuarkusTest`.** The runner is driven through the toolkit's own
fixtures: `FakeRunnerHost` (a real Vert.x server: register door, token endpoint, control socket)
and `FakeDocker` (a shell script the production process seam forks as docker), with
`RunnerRuntime.NodeOptions` shrinking the node's clocks. `WorkspacesRunnerTest` is the
`HarnessRunnerTest` shape of qits-runner-javalib; start a new case from it.

Test names are sentences describing the behaviour. A test that guards a seam is only as good as its
mutation: break the line it guards and watch it fail (the quiescence test was written that way).

## Adding a workspace frame or field

1. A record in `workspaces-runner-protocol`, added to `WorkspacesRunnerMessage`'s `permits` list.
2. `WorkspacesRunnerProtocol.Type`/`Field` constants — never a bare string at a call site.
3. Encode and decode arms in `WorkspacesRunnerCodec`, the round-trip list and the golden spelling
   in `WorkspacesRunnerCodecTest` (the permitted-subclass check fails until you do).
4. Bump `WorkspacesRunnerProtocol.CAPABILITY_VERSION` only if a peer must branch on it. A new field
   is not a bump: both codecs ignore unknown fields.
5. **Release this repository, then the maintenance bump moves the pin in qits-workspaces-service**,
   which handles the new case. This repository is the frames' only author.

The lifecycle frames (`hello`, `ack`, `heartbeat`, `backlog`, `reserve`, `nothing`, `upgrade`,
`retire`, `quarantined`, `reinstated`) are qits-runner-protocol's and are never declared here.

## Untrusted input

A `take`'s `WorkspaceSpec` arrives over a socket and becomes an argv to a root-equivalent daemon:

- **The shape is the boundary.** No network, no extra host, no docker socket, no user, no
  free-form argv, no volume name — mounts are logical (`WORKSPACE|DOT_CLAUDE|M2|PNPM`) and the
  runner names the volumes. A capability the record cannot express is a change to it.
- **The runner's own label namespace (`qits.workspaces.runner.`) is the driver's**, and a spec
  label under it is refused by the driver.
- **Every listing and removal selects by the runner's own label or a name it derived**, never
  host-wide and never by `name=`. The node is shared with a CI runner and with a person's things.
- **A spec's environment is never logged.**
- **`estate.owned` is the desired state and an absent list is malformed**, never "owns nothing":
  read as empty, it would remove every workspace on the node.

## The toolkit's calls are pacted here (qits-1163)

The token mint at qits-idp, the log export to qits-observability and the register door at
qits-workspaces-service are REST calls qits-runner-toolkit makes **on this daemon's behalf**, so
they are this daemon's consumer pacts, not the library's. `ToolkitPactTest` declares them through
`eu.wohlben.qits:qits-runner-toolkit-contracts` (test scope, pinned with `${qits.runner.version}` —
the kit refuses a toolkit of another version) and writes
`pacts/qits-workspaces-runner-daemon_qits-runner-javalib_<provider>.json`, every interaction
carrying `qits-via`. Its guard fails if the declared features drift from what the main classes
actually call. A provider whose states are not in the pinned golden masters is skipped, naming the
state it needs; once the bump brings them, rerun with `-Dgolden.update=true`, commit the new file
and declare the provider under `contracts.pacts` in `.config/qits/release.yml`.

## Formatting

`google-java-format`, 100 columns, two-space indent. Javadoc explains *why* — the tradeoff, the
alternative rejected, the failure it prevents. What the code does is the code's job.

## Building

Every maven invocation on the shared host goes through the lock:

    flock /tmp/qits-maven.lock ./mvnw -B -ntp verify
