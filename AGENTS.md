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

## The frames it sends are golden masters (qits-950)

The runner serves no HTTP, but it is a provider: of every frame it sends qits-workspaces-service
on the control socket. So it records them the way the estate's HTTP providers record their
answers (qits-546), and the service builds its message pact from the published tree,
`eu.wohlben.qits:qits-workspaces-runner-golden-masters` (`contracts.golden-masters` in
`.config/qits/release.yml`, application `qits-workspaces-runner`, maven only).

- **`RunnerProviderStates`** names the states ("a runner whose node holds a running workspace",
  "a runner asked to take a workspace whose launch fails", …) and lists `FRAMES`: per frame its
  state, its wire type and its trigger. `produce(frame, dir)` builds a fresh node (`FakeDocker`), a
  fresh host (`FakeRunnerHost`) and the real `WorkspacesRunnerDomain` under the toolkit's real
  `RunnerRuntime`, pulls the trigger (a host frame, a reconnect, the exit watch's clock) and answers
  the frame **as written on the socket**: `WireTap` is a WebSocket hop the register door points the
  runner at, keeping the runner's text frames verbatim. Never build or re-encode a frame here — the
  host's frames are the only scripted ones. The class is shared on purpose: the message-pact
  verifier (`RunnerFramesProviderPactTest`, below) drives the same states.
- **`RunnerGoldenMasterRecordingTest`** freezes each frame (`GoldenFrames`: UUIDs, instants, the
  runner's own `WorkspacesRunnerBinary.VERSION` as `2026.101.0`, and the `/proc/meminfo` numbers a
  health check reports as `0`) and writes `golden-masters/<state-slug>/frame-<type>.json` plus
  `golden-masters/index.json`. It **compares by default**; `-Dgolden.update=true` (or
  `QITS_GOLDEN_UPDATE=true`) rewrites and deletes stale files, through qits-pact-consumer's
  `GoldenFiles`.
- **`RunnerFramesGuardTest`** reads what the runner sends off the bytecode — every permitted
  subclass of `LifecycleMessage` and `WorkspacesRunnerMessage` whose constructor or static factory
  this module's main classes or qits-runner-toolkit's (its `testing/` fakes aside) call — and fails,
  naming the frame and the class that builds it, when one has no recording. It also fails on a
  recording of a frame nothing builds any more.

The index is the HTTP providers' `formatVersion: 1` shape, so the platform's readers (the CLI's
`GoldenMasterIndexParser`, which keys on `operationId`) take it as is. A frame's operation entry
replaces the HTTP fields (`method`, `path`, `headers`, `body`, `status`) with four of its own:

    {
      "operationId": "frame:stopped",          // frame:<type>, the key a pact row binds to
      "kind": "message",                       // not an HTTP answer
      "frame": "stopped",                      // the wire `type`
      "direction": "runner-to-service",
      "trigger": { "kind": "frame", "frame": "stop" },   // or event: connect|reconnect,
                                                         // schedule: heartbeat|exit-watch
      "file": "<state-slug>/frame-stopped.json",
      "frozen": { "ids": [], "instants": [], "strings": [], "listFilteredTo": null }
    }

`frozen.numbers` appears only where host facts were zeroed (`healthChecked`). A state's `params`
are strings, as everywhere: `runnerId`, and `rowId`, `requestId` or `workspaceImage` where the
state is about one.

**A new frame the runner sends** (a new record, or a lifecycle frame the toolkit starts building)
fails `RunnerFramesGuardTest` until it has a state: add it to `RunnerProviderStates.FRAMES`, rerun
the recorder with `-Dgolden.update=true`, review and commit the tree.

## The frames it receives are a consumer message pact (qits-950, A1)

qits-workspaces-service records every frame it sends a runner (`ack`, `estate`, `backlog`, `take`,
`nothing`, `stop`, `delete`, `probeLogin`, `healthCheck`, `upgrade`, `retire`, `quarantined`,
`reinstated`) as `frame:<type>` entries of its golden masters, which this repository pins in test
scope (`qits.workspaces-golden-masters.version`, the same jar the register pact reads).

- **`ServiceFramesContract`** holds one row per recorded (state, frame): the service's trigger (an
  event such as `workspaces-runner:hello`, or an operation such as `stopWorkspaceContainer`), its
  state, the frame, and what the runner must do with it. Each row runs a
  `RunnerProviderStates.Node` — the real `WorkspacesRunnerDomain` under the toolkit's real
  `RunnerRuntime`, `FakeDocker`, `FakeRunnerHost` — and writes the pact's own message text onto the
  socket with `sendRaw`; only the setting up uses typed frames. It asserts the effect on the node
  and the frames the runner answers with (`take` → `docker run` with the row's volume, labels, env
  and image, then `launched`; `delete` → stop, `rm`, the `-ws-<rowId>` volume gone, then `deleted`;
  `retire{DELETED}` → decommission and exit 0; …).
- **`ServiceFramesConsumerPactTest`** runs every row and writes
  `pacts/qits-workspaces-runner-daemon_qits-workspaces-service.json` (V4 `Asynchronous/Messages`,
  compared by default, `-Dgolden.update=true` rewrites), each interaction carrying `qits-call`
  `{app, frame}` and `qits-trigger`. The platform publishes it in the existing
  `qits-workspaces-runner-daemon-pacts-qits-workspaces-service` package (see release.yml).
- **The helpers are in `contracts/`** (`MessageGoldenMasters`, `MessageBodies`, `PactMessage`,
  `MessageContract`, `ClasspathPactLoader`), Vert.x JSON and pact-jvm only, nothing of this runner
  in them — so qits-runner-javalib's `runner-toolkit-contracts` can take them for the next runner.
  The matchers are the service's message helper's: `$.type` exact, ids/instants by regex, every
  other leaf by type, arrays `minMaxArrayLike(n, n)`, mixed-shape arrays element by element.
  `PactMessage.text(params)` swaps the service's frozen values (workspace 1) for the row's own
  (workspace 7) **only at the paths the service froze** — so an `ack`'s `slots: 1` stays 1 — and
  matches every text against the interaction's rules before it is sent.
- **`ServiceFramesGuardTest`** (A3) reads the frames the runner handles off its dispatch — the
  `SwitchBootstraps.typeSwitch` case labels in `WorkspacesRunnerDomain` and the toolkit's
  `RunnerRuntime` — and fails, naming the frame, when one has no row; and when the pinned golden
  masters record a (state, frame) no row receives.

**A new frame from the service** fails `ServiceFramesGuardTest`: once the service records it, add a
row, rerun `ServiceFramesConsumerPactTest` with `-Dgolden.update=true` and commit the pact.

## The service's pact of the frames it sends is verified here (qits-950, A2)

`RunnerFramesProviderPactTest` verifies `pacts/qits-workspaces-service_qits-workspaces-runner-daemon.json`
off the pinned `eu.wohlben.qits:qits-workspaces-service-pacts-qits-workspaces-runner-daemon`
(`qits.workspaces-service-pacts-qits-workspaces-runner-daemon.version`, test scope) with pact-jvm's
`MessageTestTarget` (`pact-jvm.version`, the service's). A `@State` per `RunnerProviderStates`
state notes the state; the `@PactVerifyProvider` for the description makes the frame with
`RunnerProviderStates.produce` — the real runner in that state, captured by `WireTap`, the
recorder's own path — never from a recorded file or hand-built JSON. It fails on a state the
runner does not answer for, on an interaction without `qits-call`/`qits-trigger`, and on a
`qits-trigger` that is not the cause the runner records for that frame. A mutation proves it: drop
`dotClaudeVolume` from `WorkspacesRunnerCodec`'s inventory encode and the three `ack:
frame:inventory` interactions go red.

## Formatting

`google-java-format`, 100 columns, two-space indent. Javadoc explains *why* — the tradeoff, the
alternative rejected, the failure it prevents. What the code does is the code's job.

## Building

Every maven invocation on the shared host goes through the lock:

    flock /tmp/qits-maven.lock ./mvnw -B -ntp verify
