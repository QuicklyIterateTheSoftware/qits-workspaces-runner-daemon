# qits-workspaces-runner-daemon

The **qits workspaces runner**: a container you start on any Linux machine with docker so that
machine runs qits workspaces. It registers once, holds one outbound connection to qits-workspaces
through the platform's public edge, takes a queued workspace when it has a free slot, and runs it as
a plain `docker run` container on this machine. A workspace stays on the runner that first ran it:
its `/workspace` volume lives here.

qits-workspaces keeps the workspace — the row, the verbs, the UI. The runner only starts, stops and
removes the containers it is told to, and reports what it has.

    ./mvnw verify                                              # the JVM suite
    docker build -t qits-workspaces-runner:dev -f docker/Dockerfile .   # the image (see docker/Dockerfile)

## Installing a runner (for the person at the machine)

A runner is **one docker container**: the static `qits-workspaces-runner` binary in the image
`registry.qits.<domain>/qits/qits-workspaces-runner:<version>`, started with docker's own restart
policy (`unless-stopped`) as its only supervisor and the host's docker socket mounted so it can
start workspaces. No service to install, no binary on the host, no systemd unit. It is never on the
platform's internal network: it reaches the platform only through its public names.

### What you need

- **Linux on x86-64** with **Docker** installed and running (`docker version` answers).
- **Outbound HTTPS** to the platform's domain (`workspaces.qits.<domain>`, `idp.qits.<domain>`,
  `registry.qits.<domain>`). **No inbound port** is ever opened.
- **root**, or a user in the `docker` group — the install line uses `sudo`.
- Disk for the workspaces: each one's `/workspace` volume, the shared caches and the workspace image
  live on this machine.

### Install

1. In the Workspaces UI, open **Runners**, create a runner and open its install panel.
2. **Copy the one line** it shows — once; it is gone on reload — and **paste it into a shell** on the
   machine. It looks like

       curl -fsSL -H 'Authorization: Bearer qits_tok_…' https://workspaces.qits.<domain>/workspaces/api/runners/install.sh | sudo env QITS_WORKSPACES_RUNNER_URL='https://workspaces.qits.<domain>' QITS_WORKSPACES_RUNNER_ID='<id>' QITS_WORKSPACES_RUNNER_REGISTRATION_TOKEN='qits_tok_…' QITS_WORKSPACES_RUNNER_SLOTS='<n>' sh

The script is qits-runner-toolkit's template, rendered by qits-workspaces; it carries no secret and
names no runner — the four values come from the line. Treat the line as a secret until the runner
has registered: the token is in it twice. The script refuses, one sentence each, when a value is
missing or `docker version` does not answer. Otherwise it logs in to `registry.qits.<domain>` with
the token in a throwaway `docker --config` (your `~/.docker/config.json` is never touched), pulls the
runner image, removes any container labelled `qits.workspaces.runner.process=<id>` (an earlier
install of this runner — **never a workspace**), keeps the state volume but deletes its
`client.json`, and starts

    docker run -d --name qits-workspaces-runner-<id8>-<version> --restart unless-stopped \
      --label qits.workspaces.runner.process=<id> --label qits.workspaces.runner.version=<version> \
      -v /var/run/docker.sock:/var/run/docker.sock \
      -v qits-workspaces-runner-state-<id8>:/var/lib/qits-workspaces-runner \
      -e QITS_WORKSPACES_RUNNER_ID=… -e QITS_WORKSPACES_RUNNER_SLOTS=… -e QITS_WORKSPACES_RUNNER_URL=… \
      -e QITS_WORKSPACES_RUNNER_REGISTRATION_TOKEN \
      registry.qits.<domain>/qits/qits-workspaces-runner:<version>

and prints `qits-workspaces-runner started; watch: docker logs -f qits-workspaces-runner-<id8>-<version>`.

### What a good first start looks like

    docker logs -f qits-workspaces-runner-<id8>-<version>

shows, within a few seconds, `workspaces-runner registered as <id>` (once, on the very first start:
the token was exchanged for the runner's own client, stored in `client.json` on the state volume at
mode 0600) and `workspaces-runner connected slots=<n>` (on every connection; the number the
Workspaces UI set for this runner wins over `QITS_WORKSPACES_RUNNER_SLOTS`). The runner mints its
access token at `https://idp.qits.<domain>/idp/token` and dials
`wss://workspaces.qits.<domain>/workspaces/runners/socket`; both come from the registration answer.

A **new runner is quarantined** — connected, given no workspaces — until its first **health check**
passes: the runner pulls the pinned workspace image (under its own client, from
`registry.qits.<domain>`), starts it detached under its label with no environment, sees it running
within 30 s and removes it. The log says `workspaces-runner health check passed: …` or why it failed.
An admin can run the check again, or greenlight the runner, from the Runners page.

### Logging in the agent CLIs (once per node)

The workspaces on one runner share one agent home: the volume
`qits-workspaces-runner-dot-claude-<id8>`, mounted at `/claude-home` (`CLAUDE_CONFIG_DIR` and
`KIMI_CODE_HOME` point into it). The runner creates it, labelled, as soon as it connects. You log in
**once per node**, on the node, and the platform never sees or moves the secret. The Runners page
shows the login state and the one command; it is

    docker run --rm -it --user 1000 --entrypoint claude -v <dotClaudeVolume>:/claude-home -e HOME=/claude-home -e CLAUDE_CONFIG_DIR=/claude-home <registry.qits.<d>/qits/workspace:<pin>>

— `<dotClaudeVolume>` being `qits-workspaces-runner-dot-claude-<id8>` and the image the workspace
image qits-workspaces pins. `--user 1000` is the workspace's own user, not the image's default
root: a login written as root would be unreadable by the workspace containers that mount the same
volume. `--entrypoint claude` is needed for the same reason it is on every other command here — the
image's default entrypoint starts the workspace daemon, not the agent CLI. For Kimi Code:

    docker run --rm -it --user 1000 --entrypoint kimi -v <dotClaudeVolume>:/claude-home -e HOME=/claude-home -e KIMI_CODE_HOME=/claude-home/.kimi-code <registry.qits.<d>/qits/workspace:<pin>> login

It runs the workspace image the runner already pulled (it pulls it after every connection when it is
not here), so it needs no registry login. Then press **re-check** on the Runners page.

How the runner knows: it runs the same image against the same volume and asks —
`claude auth status` (its `loggedIn`), and for Kimi whether a credential file exists under
`/claude-home/.kimi-code`. It reads presence only: what `auth status` prints names the account and
is never logged, kept or sent. The state reads PRESENT, ABSENT, or UNKNOWN when the probe could not
run (the image is not here yet, docker refused). It is checked after every connection, on
**re-check**, every 5 minutes while either login is ABSENT, and hourly otherwise.

**A deleted runner's login goes with it**: the agent home is labelled
`qits.workspaces.runner=<id>`, and deleting the runner removes it with everything else of the
runner's. A new runner on the same node is logged in again.

### Updating

Nothing to do: the runner updates itself. qits-workspaces pins the runner version it hands out, and
a runner that connects with any other version is told to become it. **An update never stops a
workspace.** The old runner pulls the new image under its own client, starts its successor
`qits-workspaces-runner-<id8>-<new>` at once — with its own `docker inspect`ed parameters, the same
state volume and no registration token — and leaves when qits-workspaces retires it. It does not
wait for workspaces to finish, because a workspace container outlives the runner process: the
successor adopts the running ones by their label. Only a start, stop or delete half done is waited
for. The successor removes its predecessor's container and image after its first connection.

If the successor has not taken over within `QITS_WORKSPACES_RUNNER_ROLLOVER_TIMEOUT` (180 s), the old
runner removes it and tries again later. `QITS_WORKSPACES_RUNNER_SELF_UPDATE=false` makes a runner
ignore updates (for a runner something else replaces).

### Rotating the registration, or reinstalling

**Replace registration token** on the Runners page and paste the new line exactly as the first time.
The script replaces the runner's container, keeps its state volume, and the new token registers. The
workspace containers and volumes are not touched.

### Deleting a runner

Delete it on the Runners page. That is refused while the runner owns an active workspace — discard
or integrate those first. Once deleted, the runner hears it (or finds its credentials revoked),
stops its workspaces, takes its own restart policy away and starts a one-shot helper,
`qits-workspaces-runner-<id8>-decommission`, which removes the runner's own container, then
**everything labelled `qits.workspaces.runner=<id>`** — the workspace containers and volumes, the
agent home and the caches, including the node's agent login — then the state volume, and finally
itself. Nothing without that label is touched.

By hand, should the runner not be running when it is deleted:

    docker rm -f $(docker ps -aq --filter label=qits.workspaces.runner.process=<id>)
    docker rm -f $(docker ps -aq --filter label=qits.workspaces.runner=<id>)
    docker volume rm $(docker volume ls -q --filter label=qits.workspaces.runner=<id>)
    docker volume rm qits-workspaces-runner-state-<id8>

### The node namespace

Everything the runner creates is named from its id (`<id8>` its first eight characters) and carries
its labels, so it can share a machine with a CI runner, another workspaces runner and your own
containers. It never touches `qits-ci-runner*`, `qits-buildkitd*` or anything unlabelled, and never
runs `docker system prune`, `image prune -a` or `volume prune`.

| What | Name | Labels |
|---|---|---|
| the runner | `qits-workspaces-runner-<id8>-<version>` | `qits.workspaces.runner.process=<id>`, `qits.workspaces.runner.version=<version>` |
| its state (`client.json`, `heartbeat`) | volume `qits-workspaces-runner-state-<id8>` at `/var/lib/qits-workspaces-runner` | — |
| a workspace (row id `<rowId>`) | container and volume `qits-workspaces-runner-ws-<rowId>-<id8>` | `qits.workspaces.runner=<id>`, `qits.workspaces.runner.workspace=<rowId>` (the container also `qits.workspaces.runner.spec=<sha256>`) |
| the agent home | volume `qits-workspaces-runner-dot-claude-<id8>` | `qits.workspaces.runner=<id>` |
| the caches | volumes `qits-workspaces-runner-m2-<id8>`, `qits-workspaces-runner-pnpm-<id8>` | `qits.workspaces.runner=<id>` |
| the health check | container `qits-workspaces-runner-health-check-<id8>`, seconds long | `qits.workspaces.runner=<id>` |
| the login probe | two `--rm` containers of the workspace image, seconds long | `qits.workspaces.runner=<id>` |
| the decommission helper | `qits-workspaces-runner-<id8>-decommission` | `qits.workspaces.runner.decommission=<id>` |

### Disk

The runner cleans up after itself; there is nothing to configure. Every 6 hours (and once after it
connects) it removes old images of its own repository, dangling layers (`docker image prune -f`),
and workspace images other than the one qits-workspaces currently pins that no container was created
from. An image a container still uses is never removed.

Workspaces follow qits-workspaces' list of the rows this runner owns, which it sends on every
connection: a workspace container of a row no longer on it is removed at once, and its `/workspace`
volume at the next of those passes. Nothing is removed before that list has arrived on the current
connection, and a container or volume without the runner's label is never a candidate. A workspace
container runs as uid 1000 with no restart policy: it stops when qits-workspaces stops it or when it
ends by itself, and a docker restart does not bring it back behind qits-workspaces' back.

### Environment

The install script passes the first four; the rest are for an operator with a reason (put them in
the line's `env …`; the script passes `DOCKER_TIMEOUT` and `ROLLOVER_TIMEOUT` when set). A successor
inherits whatever its predecessor was given.

| Variable | Meaning | Default |
|---|---|---|
| `QITS_WORKSPACES_RUNNER_URL` | qits-workspaces' public url, `https://workspaces.qits.<domain>`. | required |
| `QITS_WORKSPACES_RUNNER_ID` | The runner id the Runners page minted. | required |
| `QITS_WORKSPACES_RUNNER_REGISTRATION_TOKEN` | One-time registration token, needed until registered; a different one later registers again. | required until registered |
| `QITS_WORKSPACES_RUNNER_SLOTS` | How many workspaces this machine runs at once. Advertised; the Runners page's number wins. | `1` |
| `QITS_WORKSPACES_RUNNER_STATE_DIR` | Where `client.json` lives — the state volume's mount point. | `/var/lib/qits-workspaces-runner` |
| `QITS_WORKSPACES_RUNNER_DOCKER_BINARY` | The docker CLI. | `docker` (the image's) |
| `QITS_WORKSPACES_RUNNER_DOCKER_TIMEOUT` | Seconds any one docker call may take. | `120` |
| `QITS_WORKSPACES_RUNNER_ROLLOVER_TIMEOUT` | Seconds a successor has to take over. | `180` |
| `QITS_WORKSPACES_RUNNER_SELF_UPDATE` | `false`: ignore updates; something else replaces this runner. | `true` |
| `QITS_WORKSPACES_RUNNER_TELEMETRY_URL` | Where the runner's own log is shipped (OTLP). Empty switches it off. | derived: `https://observability.qits.<domain>/observability/api/otel` |
| `QITS_WORKSPACES_RUNNER_PRINT_VERSION` | `1`: print the version and exit 0, needing nothing else. | unset |

The runner's log is `docker logs` on the machine, and also lands on the platform under
`_service/qits-workspaces-runner`, told apart by `qits.workspaces.runner.id`.

### Exit codes

A healthy runner never exits; docker restarts it after any of these. The clean exit, 0, is a runner
retired by qits-workspaces (after an update) or decommissioned (after a delete), which takes its own
restart policy away first.

| Code | Meaning | What to do |
|---|---|---|
| 0 | Retired, or decommissioned because the runner was deleted. | Nothing. |
| 2 | A variable is missing or unparseable (the log line names it), or the runner is not registered and has no token. Also: the image run with no environment. | Re-paste the install line. |
| 3 | Registration could not reach qits-workspaces, or it answered 5xx. | Nothing — the restart is the retry. |
| 4 | qits-workspaces speaks a protocol version this runner does not. | Re-paste the install line it currently hands out. |
| 5 | qits-workspaces refused the registration (4xx); its answer is in the log. | Replace the registration token and paste the new line. |
| 6 | The state directory is unusable, or `client.json` is not a usable client. | Replace the registration token and paste the new line. |

`qits-workspaces-runner health` is the container healthcheck, answered before anything starts: exit
0 while `<state dir>/heartbeat` is younger than 60 s (the runner is connected), 1 when it is missing
or stale, 2 when the state directory does not exist.

## Layout

- `workspaces-runner-protocol/` — published as `eu.wohlben.qits:qits-workspaces-runner-protocol`:
  the workspace frames (`WorkspacesRunnerMessage`, `WorkspacesRunnerCodec`), the socket path, the
  capability version, the vocabulary, the identity roots, and `WorkspacesRunnerBinary.VERSION` — the
  image tag, so qits-workspaces' pin of this jar is the pin of the runner it hands out. Depends on
  `qits-runner-protocol` only.
- `workspaces-runner/` — the binary: `Main`, `WorkspacesRunnerIdentity`, `WorkspacesRunnerDomain`
  (the frames, the slots, the estate reconcile, the exit watch), `WorkspaceLauncher` (a take's
  ladder: running → launched, same spec → `docker start`, other spec or none → created),
  `WorkspaceContainers`, `NodeVolumes`, `LoginProbe`, `NodeHealthCheck`, `WorkspaceImagesPass` and
  `WorkspaceVolumesPass`, on `qits-runner-toolkit`.
- `docker/` — the native musl compile and the image (`Dockerfile`), and the toolchain
  (`Dockerfile.musl-builder`, a copy of qits-ci-runner-daemon's).
- `.config/qits/release.yml` — the gate (the suite, an image smoke test) and the release (the image,
  then the jar).

## The conversation

The lifecycle frames are qits-runner-protocol's, byte-identical to CI's; the workspace frames are
this repository's. A row id is a JSON number.

    runner → hello{runnerVersion, capabilityVersion, slots, capabilities, heldWorkspaces}
    ack{slots, adoptedWorkspaces} ← … estate{owned:[rowId], workspaceImage}
    runner → inventory{containers:[{rowId, running, specHash}], volumes:[rowId], dotClaudeVolume}
    backlog{queued} … runner → reserve … take{rowId, spec} | nothing
    runner → launched{rowId, containerId} | launchFailed{rowId, detail}
    stop{rowId} → stopped{rowId}      delete{rowId} → deleted{rowId}
    runner → exited{rowId, exitCode}
    probeLogin → loginState{claude, kimi, checkedAt}        (PRESENT | ABSENT | UNKNOWN)
    healthCheck → healthChecked{ok, detail}
    upgrade{version, image, sha256} … retire{reason, kind}
    quarantined{reason, since} … reinstated{by}

`spec` is `{image, env{}, mounts[{volume: WORKSPACE|DOT_CLAUDE|M2|PNPM, target}], labels{},
memoryLimit, memorySwapLimit, pidsLimit, cpus, oomScoreAdj, init}` — no network, no extra hosts, no
docker socket, no user; the runner names the volumes itself.

## Releasing

A release request gates on `./mvnw verify` and a smoke test of the built image (it prints this
tree's version when asked, and exits 2 with no environment). The release pushes the image first and
publishes the protocol jar after it: the jar is the pin qits-workspaces-service resolves, and it
must not resolve before its image exists. See `AGENTS.md` for the working rules.
