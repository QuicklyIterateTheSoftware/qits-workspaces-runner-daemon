package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.runner.toolkit.Decommission;
import eu.wohlben.qits.runner.toolkit.HostFacts;
import eu.wohlben.qits.runner.toolkit.Housekeeping;
import eu.wohlben.qits.runner.toolkit.RunnerExit;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerRuntime;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import eu.wohlben.qits.runner.toolkit.SelfUpdate;
import eu.wohlben.qits.runner.toolkit.Telemetry;
import eu.wohlben.qits.runner.toolkit.health.DefaultHealthChecks;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import eu.wohlben.qits.runner.toolkit.testing.FakeRunnerHost;
import eu.wohlben.qits.workspacesrunner.protocol.Delete;
import eu.wohlben.qits.workspacesrunner.protocol.Estate;
import eu.wohlben.qits.workspacesrunner.protocol.HealthCheck;
import eu.wohlben.qits.workspacesrunner.protocol.Mount;
import eu.wohlben.qits.workspacesrunner.protocol.ProbeLogin;
import eu.wohlben.qits.workspacesrunner.protocol.Stop;
import eu.wohlben.qits.workspacesrunner.protocol.Take;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspaceSpec;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerCodec;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerMessage;
import io.vertx.core.Vertx;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * <b>The runner's provider states</b>, and how each frame the runner sends qits-workspaces-service
 * is made to be sent in one (qits-950). A state is a node and a host as {@link FakeDocker} and
 * {@link FakeRunnerHost} script them; a {@link Frame} names the state, the frame and what made it
 * be sent (its trigger), and {@link #produce} runs the real {@link WorkspacesRunnerDomain} under
 * qits-runner -toolkit's real {@link RunnerRuntime} in that state, pulls the trigger and answers
 * the frame as the runner wrote it on the socket ({@link WireTap}). Nothing here builds a frame the
 * runner sends; only the frames the <em>host</em> sends ({@code ack}, {@code estate}, {@code take},
 * …) are scripted.
 *
 * <p>A class of its own, not the recorder's, because two suites drive the same states: {@code
 * RunnerGoldenMasterRecordingTest} records what each produces into {@code golden-masters/}, and the
 * message-pact verifier this repository gains next (qits-950 A2) re-produces each frame from the
 * state its consumer's pact names. One fresh runner per frame: a {@code stop} and a {@code delete}
 * of one row in one session would each see what the other left.
 */
final class RunnerProviderStates {

  static final RunnerIdentity IDENTITY = WorkspacesRunnerIdentity.IDENTITY;

  static final RunnerCodec<WorkspacesRunnerMessage> CODEC =
      new RunnerCodec<>(IDENTITY.vocabulary(), new WorkspacesRunnerCodec());

  static final String RUNNER_ID = "r1";

  /** The row every workspace state is about. */
  static final long ROW = 5;

  static final String WORKSPACE_IMAGE = "registry.example:5000/qits/workspace:2026.1004.1";

  /** The {@code requestId} qits-workspaces puts on a {@code healthCheck}. */
  static final String REQUEST_ID = "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f";

  /** The runner's own container, as {@link RunnerRuntime.NodeOptions#self} names it. */
  private static final String SELF = "0123456789ab";

  private static final Instant NOW = Instant.parse("2026-10-05T04:38:19Z");

  // --- the states --------------------------------------------------------------------------------

  static final String NO_WORKSPACES = "a runner whose node holds no workspaces";
  static final String RECONNECTING =
      "a runner reconnecting while its node holds a running workspace";
  static final String RUNNING = "a runner whose node holds a running workspace";
  static final String STOPPED = "a runner whose node holds a stopped workspace";
  static final String FREE_SLOT = "a runner with a free slot and queued work";
  static final String TAKE = "a runner asked to take a new workspace";
  static final String TAKE_FAILS = "a runner asked to take a workspace whose launch fails";
  static final String EXITS = "a runner whose running workspace exits by itself";
  static final String HEALTHY = "a runner whose node is healthy and logged in";
  static final String LOGGED_OUT = "a runner whose node is not logged in";
  static final String NO_ESTATE = "a runner that has heard no estate yet";

  /**
   * One state's params: the values its frames are about, as the index records them — what a
   * consumer lays over the recording, and what a verifier is handed back.
   */
  static Map<String, String> params(String state) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("runnerId", RUNNER_ID);
    switch (state) {
      case RECONNECTING, RUNNING, STOPPED, TAKE, TAKE_FAILS, EXITS ->
          params.put("rowId", Long.toString(ROW));
      case HEALTHY, NO_ESTATE -> params.put("requestId", REQUEST_ID);
      default -> {}
    }
    switch (state) {
      case TAKE, TAKE_FAILS, HEALTHY, LOGGED_OUT -> params.put("workspaceImage", WORKSPACE_IMAGE);
      default -> {}
    }
    return params;
  }

  // --- the frames --------------------------------------------------------------------------------

  /**
   * What made a frame be sent — the {@code qits-trigger} of the pact row built from it: a host
   * frame ({@code kind: frame}), a socket event ({@code kind: event}) or one of the runner's own
   * clocks ({@code kind: schedule}).
   */
  record Trigger(String kind, String name) {
    static Trigger frame(String type) {
      return new Trigger("frame", type);
    }

    static Trigger event(String event) {
      return new Trigger("event", event);
    }

    static Trigger schedule(String schedule) {
      return new Trigger("schedule", schedule);
    }
  }

  /** How one frame is produced: the state set, the trigger pulled, the frame awaited. */
  @FunctionalInterface
  interface Producer {
    String produce(Node node) throws Exception;
  }

  /**
   * One recorded frame.
   *
   * @param type the frame's wire {@code type}
   */
  record Frame(String state, String type, Trigger trigger, Producer producer) {}

  /** Every frame the runner sends, in each state it is recorded in. */
  static final List<Frame> FRAMES =
      List.of(
          new Frame(
              NO_WORKSPACES,
              "hello",
              Trigger.event("connect"),
              node -> node.connect().tap.await("hello", 0, 1)),
          new Frame(
              NO_WORKSPACES,
              "heartbeat",
              Trigger.schedule("heartbeat"),
              node -> node.connect().tap.await("heartbeat", 0, 1)),
          new Frame(
              NO_WORKSPACES,
              "inventory",
              Trigger.frame("ack"),
              node -> node.connect().tap.await("inventory", 0, 1)),
          new Frame(
              RECONNECTING,
              "hello",
              Trigger.event("reconnect"),
              node -> {
                node.connect().holding(ROW);
                node.host.socket.close();
                return node.tap.await("hello", 0, 2);
              }),
          new Frame(
              RUNNING,
              "inventory",
              Trigger.frame("ack"),
              node -> node.connect().tap.await("inventory", 0, 1)),
          new Frame(
              RUNNING,
              "stopped",
              Trigger.frame("stop"),
              node -> node.connect().holding(ROW).answer(new Stop(ROW), "stopped")),
          new Frame(
              RUNNING,
              "deleted",
              Trigger.frame("delete"),
              node -> node.connect().holding(ROW).answer(new Delete(ROW), "deleted")),
          new Frame(
              STOPPED,
              "inventory",
              Trigger.frame("ack"),
              node -> node.connect().tap.await("inventory", 0, 1)),
          new Frame(
              FREE_SLOT,
              "reserve",
              Trigger.frame("backlog"),
              node -> node.connect().tap.await("reserve", 0, 1)),
          new Frame(
              TAKE,
              "launched",
              Trigger.frame("take"),
              node -> node.connect().tap.await("launched", 0, 1)),
          new Frame(
              TAKE_FAILS,
              "launchFailed",
              Trigger.frame("take"),
              node -> node.connect().tap.await("launchFailed", 0, 1)),
          new Frame(
              EXITS,
              "exited",
              Trigger.schedule("exit-watch"),
              node -> {
                node.connect().holding(ROW);
                int from = node.tap.mark();
                node.seen(ROW, "exited", 137, "h5");
                return node.tap.await("exited", from, 1);
              }),
          new Frame(
              HEALTHY,
              "loginState",
              Trigger.frame("estate"),
              node -> node.connect().tap.await("loginState", 0, 1)),
          new Frame(
              HEALTHY,
              "healthChecked",
              Trigger.frame("healthCheck"),
              node -> {
                node.connect().tap.await("loginState", 0, 1);
                node.awaitTrue(() -> node.docker.volumes().containsKey(node.volumes.dotClaude()));
                return node.answer(new HealthCheck(REQUEST_ID), "healthChecked");
              }),
          new Frame(
              LOGGED_OUT,
              "loginState",
              Trigger.frame("estate"),
              node -> node.connect().tap.await("loginState", 0, 1)),
          new Frame(
              NO_ESTATE,
              "loginState",
              Trigger.frame("probeLogin"),
              node -> {
                node.connect().tap.await("inventory", 0, 1);
                return node.answer(new ProbeLogin(), "loginState");
              }),
          new Frame(
              NO_ESTATE,
              "healthChecked",
              Trigger.frame("healthCheck"),
              node -> {
                node.connect().tap.await("inventory", 0, 1);
                return node.answer(new HealthCheck(REQUEST_ID), "healthChecked");
              }));

  private RunnerProviderStates() {}

  /**
   * {@code frame} as the runner writes it in its state: a fresh node and host under {@code
   * workDir}, the state set, the trigger pulled, and everything torn down again.
   */
  static String produce(Frame frame, Path workDir) throws Exception {
    try (Node node = new Node(workDir)) {
      node.set(frame.state());
      return frame.producer().produce(node);
    }
  }

  // --- the node ----------------------------------------------------------------------------------

  /** One runner, its host, its node's docker and the tap between runner and host. */
  static final class Node implements AutoCloseable {

    final Vertx vertx = Vertx.vertx();
    final FakeRunnerHost host;
    final FakeDocker docker;
    final WireTap tap;
    final NodeVolumes volumes = new NodeVolumes(IDENTITY, RUNNER_ID);
    private final Path stateDir;
    private WorkspacesRunnerDomain domain;
    private RunnerRuntime<WorkspacesRunnerMessage> runtime;
    private CompletableFuture<Integer> exit;

    Node(Path workDir) throws Exception {
      this.stateDir = Files.createDirectories(workDir.resolve("state"));
      this.host = new FakeRunnerHost(vertx, IDENTITY, CODEC);
      this.docker = new FakeDocker(Files.createDirectories(workDir.resolve("docker")));
      this.tap = new WireTap(vertx, host, RUNNER_ID);
      // What every health check reads besides its own subject, as WorkspacesRunnerTest scripts it.
      docker.answer("version", 0, "27.3.1\n", "");
      docker.answer("volume-inspect", 0, "|2026-10-01T00:00:00Z|\n", "");
    }

    /** Script the node and the host for {@code state}; nothing runs until {@link #connect}. */
    void set(String state) throws Exception {
      switch (state) {
        case NO_WORKSPACES -> ackWith(1, 0, null);
        case RECONNECTING, RUNNING, EXITS -> {
          seen(ROW, "running", 0, "h5");
          rowVolume(ROW);
          ackWith(1, 0, null);
        }
        case STOPPED -> {
          seen(ROW, "exited", 0, "h5");
          rowVolume(ROW);
          ackWith(1, 0, null);
        }
        case FREE_SLOT -> ackWith(1, 1, null);
        case TAKE -> takeOnFirstReserve();
        case TAKE_FAILS -> {
          docker.answerFor(
              "run",
              WORKSPACE_IMAGE,
              125,
              "",
              "docker: Error response from daemon: no space left on device.");
          takeOnFirstReserve();
        }
        case HEALTHY -> healthyNode();
        case LOGGED_OUT -> {
          docker.answerFor("run", "status", 1, "{\"loggedIn\": false}\n", "");
          docker.answerFor("run", LoginProbe.KIMI_CHECK, 1, "", "");
          ackWith(1, 0, new Estate(List.of(), WORKSPACE_IMAGE));
        }
        case NO_ESTATE -> ackWith(1, 0, null);
        default -> throw new IllegalArgumentException("no such provider state: " + state);
      }
    }

    /**
     * The node of {@code WorkspacesRunnerTest}'s every-default-check case, without the other
     * tenants: row 1's exited container and its volume, the node's m2 cache, the runner's own
     * container and a self-test probe that runs, both logins present, and the estate owning row 1.
     */
    private void healthyNode() throws Exception {
      String probe = "qits-workspaces-runner-health-check-" + RUNNER_ID;
      String self = IDENTITY.containerName(RUNNER_ID, WorkspacesRunnerBinary.VERSION);
      String ws1 = names(1);
      Map<String, String> row1 =
          Map.of(IDENTITY.ownerLabel(), RUNNER_ID, IDENTITY.workLabel(), "1");
      docker.container(ws1, row1);
      docker.volume(ws1, row1);
      docker.volume(volumes.m2(), Map.of(IDENTITY.ownerLabel(), RUNNER_ID));
      docker.container(self, Map.of(IDENTITY.processLabel(), RUNNER_ID));
      docker.answerFor(
          "inspect",
          ws1,
          0,
          "c1full|/"
              + ws1
              + "|exited|2026-10-05T04:00:00.123456789Z|2026-10-05T04:30:00Z|137|"
              + WORKSPACE_IMAGE
              + "|1|<no value>\n",
          "");
      docker.answerFor(
          "inspect",
          self,
          0,
          "selffull|/"
              + self
              + "|running|2026-10-05T03:00:00Z|"
              + DefaultHealthChecks.DOCKER_ZERO_TIME
              + "|0|"
              + "registry.example:5000/qits/qits-workspaces-runner:"
              + WorkspacesRunnerBinary.VERSION
              + "|<no value>|"
              + WorkspacesRunnerBinary.VERSION
              + "\n",
          "");
      docker.answerFor("inspect", probe, 0, "abc|running/none|2026-10-05T04:38:19Z\n", "");
      docker.answerFor("volume-inspect", ws1, 0, ws1 + "|2026-10-04T10:00:00+02:00|1\n", "");
      docker.answerFor(
          "volume-inspect", volumes.m2(), 0, volumes.m2() + "|2026-10-01T09:00:00Z|\n", "");
      docker.answerFor("image-inspect", WORKSPACE_IMAGE, 0, "sha256:ws|2026-10-04T08:00:00Z\n", "");
      docker.answerFor("run", "status", 0, "{\"loggedIn\": true}\n", "");
      docker.answerFor("run", LoginProbe.KIMI_CHECK, 0, "", "");
      ackWith(1, 0, new Estate(List.of(1L), WORKSPACE_IMAGE));
    }

    /** Start the runner; it registers, dials (through the tap) and says hello. */
    Node connect() throws Exception {
      RunnerSettings settings =
          RunnerSettings.parse(IDENTITY, env()::get)
              .withTiming(new RunnerSettings.Timing(300, 50, 200, 5_000, 300_000, 5));
      domain =
          new WorkspacesRunnerDomain(
              settings,
              new WorkspacesRunnerDomain.Timing(
                  Duration.ofMillis(600),
                  Duration.ofMillis(50),
                  Duration.ofMillis(100),
                  Duration.ofSeconds(60),
                  Duration.ofSeconds(60)),
              Clock.fixed(NOW, ZoneOffset.UTC));
      runtime =
          new RunnerRuntime<>(
              vertx,
              IDENTITY,
              settings,
              domain,
              new HostFacts(true, "amd64", "linux"),
              Telemetry.off(),
              new RunnerRuntime.NodeOptions(
                  Optional.of(SELF),
                  new SelfUpdate.Settings(100, 400, 3_000, 400, 50),
                  new Decommission.Settings(200, 20, 2),
                  new Housekeeping.Settings(TimeUnit.HOURS.toMillis(6)),
                  Clock.systemUTC()));
      exit = CompletableFuture.supplyAsync(runtime::run);
      return this;
    }

    /** Wait until the runner's slot ledger holds exactly {@code rows}. */
    Node holding(long... rows) throws InterruptedException {
      List<String> expected = java.util.Arrays.stream(rows).mapToObj(Long::toString).toList();
      awaitTrue(() -> runtime.session() != null && runtime.session().held().equals(expected));
      return this;
    }

    /** Send {@code frame} as the host, and answer the first {@code type} the runner sends after. */
    String answer(RunnerMessage frame, String type) throws InterruptedException {
      int from = tap.mark();
      host.send(frame);
      return tap.await(type, from, 1);
    }

    /** The row's container on the node, as docker's inspect answers it from now on. */
    void seen(long rowId, String status, int exitCode, String specHash) throws Exception {
      docker.container(
          names(rowId),
          Map.of(IDENTITY.ownerLabel(), RUNNER_ID, IDENTITY.workLabel(), Long.toString(rowId)));
      docker.answerFor(
          "inspect",
          names(rowId),
          0,
          "c" + rowId + "|" + status + "|" + exitCode + "|" + rowId + "|" + specHash + "\n",
          "");
    }

    private void rowVolume(long rowId) throws Exception {
      docker.volume(
          names(rowId),
          Map.of(IDENTITY.ownerLabel(), RUNNER_ID, IDENTITY.workLabel(), Long.toString(rowId)));
    }

    /** {@code qits-workspaces-runner-ws-<rowId>-r1}: a row's container and its volume. */
    private static String names(long rowId) {
      return new WorkspaceNames(IDENTITY, RUNNER_ID).container(rowId);
    }

    /** Ack every hello with {@code slots}, say {@code backlog} items queue, then the estate. */
    private void ackWith(int slots, int backlog, Estate estate) {
      host.script =
          (h, message) -> {
            if (message instanceof Hello) {
              h.send(new Ack(IDENTITY.capabilityVersion(), slots));
              h.send(new Backlog(backlog));
              if (estate != null) {
                h.send(estate);
              }
            }
          };
    }

    /** Answer the first reserve with a take of {@link #ROW}, as qits-workspaces composes it. */
    private void takeOnFirstReserve() {
      BiConsumer<FakeRunnerHost, RunnerMessage> script =
          (h, message) -> {
            if (message instanceof Hello) {
              h.send(new Ack(IDENTITY.capabilityVersion(), 1));
              h.send(new Backlog(1));
            } else if (message instanceof Reserve && h.all(Reserve.class).size() == 1) {
              h.send(new Take(ROW, spec()));
            }
          };
      host.script = script;
    }

    /** {@code WorkspacesRunnerTest}'s full spec: what qits-workspaces composes for a workspace. */
    private static WorkspaceSpec spec() {
      Map<String, String> env = new LinkedHashMap<>();
      env.put("TZ", "UTC");
      env.put("QITS_WORKSPACE_DAEMON_WORKSPACE_ID", "w5");
      Map<String, String> labels = new LinkedHashMap<>();
      labels.put("qits.workspace", "w5");
      labels.put("qits.repository", "qits-qits");
      return new WorkspaceSpec(
          WORKSPACE_IMAGE,
          env,
          List.of(
              new Mount(Mount.Volume.WORKSPACE, "/workspace"),
              new Mount(Mount.Volume.DOT_CLAUDE, "/claude-home"),
              new Mount(Mount.Volume.M2, "/caches/m2"),
              new Mount(Mount.Volume.PNPM, "/caches/pnpm")),
          labels,
          "4g",
          "4g",
          512L,
          "2",
          500,
          true);
    }

    private Map<String, String> env() {
      Map<String, String> env = new HashMap<>();
      env.put(IDENTITY.env(RunnerSettings.URL), host.base());
      env.put(IDENTITY.env(RunnerSettings.ID), RUNNER_ID);
      env.put(IDENTITY.env(RunnerSettings.REGISTRATION_TOKEN), "t");
      env.put(IDENTITY.env(RunnerSettings.STATE_DIR), stateDir.toString());
      env.put(IDENTITY.env(RunnerSettings.SLOTS), "2");
      env.put(IDENTITY.env(RunnerSettings.DOCKER_BINARY), docker.binary());
      env.put(IDENTITY.env(RunnerSettings.DOCKER_TIMEOUT), "10");
      return env;
    }

    void awaitTrue(BooleanSupplier condition) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (!condition.getAsBoolean()) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("the condition never held; the runner sent " + docker.calls());
        }
        Thread.sleep(20);
      }
    }

    @Override
    public void close() throws Exception {
      if (runtime != null) {
        runtime.stop(RunnerExit.OK);
        exit.get(10, TimeUnit.SECONDS);
      }
      if (domain != null) {
        domain.close();
      }
      tap.close();
      host.close();
      vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
      awaitNoFakeDocker();
    }

    /**
     * Every docker this node forked has ended, as {@code WorkspacesRunnerTest}'s teardown waits.
     */
    private static void awaitNoFakeDocker() throws InterruptedException {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (ProcessHandle.current().descendants().anyMatch(ProcessHandle::isAlive)) {
        if (System.nanoTime() > until) {
          ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
          return;
        }
        Thread.sleep(10);
      }
    }
  }
}
