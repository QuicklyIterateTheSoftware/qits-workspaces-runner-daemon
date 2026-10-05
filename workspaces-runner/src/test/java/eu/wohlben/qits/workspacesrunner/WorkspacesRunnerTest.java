package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerWire;
import eu.wohlben.qits.runner.protocol.Upgrade;
import eu.wohlben.qits.runner.toolkit.Decommission;
import eu.wohlben.qits.runner.toolkit.HostFacts;
import eu.wohlben.qits.runner.toolkit.Housekeeping;
import eu.wohlben.qits.runner.toolkit.RunnerExit;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerLauncher;
import eu.wohlben.qits.runner.toolkit.RunnerRuntime;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import eu.wohlben.qits.runner.toolkit.SelfUpdate;
import eu.wohlben.qits.runner.toolkit.Telemetry;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import eu.wohlben.qits.runner.toolkit.testing.FakeRunnerHost;
import eu.wohlben.qits.workspacesrunner.protocol.Delete;
import eu.wohlben.qits.workspacesrunner.protocol.Deleted;
import eu.wohlben.qits.workspacesrunner.protocol.Estate;
import eu.wohlben.qits.workspacesrunner.protocol.HealthCheck;
import eu.wohlben.qits.workspacesrunner.protocol.HealthChecked;
import eu.wohlben.qits.workspacesrunner.protocol.LaunchFailed;
import eu.wohlben.qits.workspacesrunner.protocol.LoginPresence;
import eu.wohlben.qits.workspacesrunner.protocol.LoginState;
import eu.wohlben.qits.workspacesrunner.protocol.ProbeLogin;
import eu.wohlben.qits.workspacesrunner.protocol.Stop;
import eu.wohlben.qits.workspacesrunner.protocol.Stopped;
import eu.wohlben.qits.workspacesrunner.protocol.Take;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspaceSpec;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerCodec;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerMessage;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspaces runner's life, end to end, against qits-runner-toolkit's own fixtures: {@link
 * FakeRunnerHost} (register door, token endpoint, control socket, each scripted here) and {@link
 * FakeDocker} (a script the production process seam forks as docker). The runtime is the toolkit's
 * {@link RunnerRuntime} with {@link WorkspacesRunnerDomain}, exactly what {@code Main} hands
 * {@code RunnerLauncher}; only the node's clocks are shrunk.
 */
@EnabledOnOs(OS.LINUX)
class WorkspacesRunnerTest {

  private static final RunnerIdentity IDENTITY = WorkspacesRunnerIdentity.IDENTITY;

  private static final RunnerCodec<WorkspacesRunnerMessage> CODEC =
      new RunnerCodec<>(IDENTITY.vocabulary(), new WorkspacesRunnerCodec());

  /** The runner's own container, as the suite names it ({@link RunnerRuntime.NodeOptions#self}). */
  private static final String SELF = "0123456789ab";

  private static final String RUNNER_ID = "r1";

  private static final String WORKSPACE_IMAGE = "registry.example:5000/qits/workspace:2026.1004.1";

  private static final Instant NOW = Instant.parse("2026-10-05T04:38:19Z");

  private final Vertx vertx = Vertx.vertx();
  private FakeRunnerHost host;
  private FakeDocker docker;
  private RunnerRuntime<WorkspacesRunnerMessage> runner;
  private CompletableFuture<Integer> exit;

  @TempDir Path state;
  @TempDir Path dockerDir;

  @BeforeEach
  void setUp() throws Exception {
    host = new FakeRunnerHost(vertx, IDENTITY, CODEC);
    docker = new FakeDocker(dockerDir);
  }

  @AfterEach
  void tearDown() throws Exception {
    stopRunner();
    host.close();
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  // ---- register, bearer, Hello ------------------------------------------------------------------

  @Test
  void itRegistersAtTheWorkspacesDoorOnceAndAgainOnlyForANewToken() throws Exception {
    ackWith(2, 0);
    start("token-1");
    host.await(Hello.class, 1);

    assertEquals(List.of("/workspaces/api/runners/r1/register"), host.registerPaths);
    assertEquals("Bearer token-1", host.registerAuthorizations.getFirst());
    assertEquals(
        PosixFilePermissions.fromString("rw-------"),
        Files.getPosixFilePermissions(state.resolve("client.json")));

    stopRunner();
    start("token-1");
    host.await(Hello.class, 2);
    assertEquals(1, host.registerPaths.size(), "a restart does not register again");

    stopRunner();
    start("token-2");
    host.await(Hello.class, 3);
    assertEquals(2, host.registerPaths.size(), "a new token registers again");
  }

  @Test
  void theHelloSaysTheVersionAndTheCapabilitiesAndTheBearerIsClientSecretPost() throws Exception {
    ackWith(2, 0);
    start("t");

    Hello hello = host.await(Hello.class);
    assertEquals(WorkspacesRunnerBinary.VERSION, hello.runnerVersion());
    assertEquals(1, hello.capabilityVersion());
    assertEquals(2, hello.slots());
    assertEquals(new RunnerCapabilities(true, "amd64", "linux", Map.of()), hello.capabilities());
    assertEquals(List.of(), hello.held());
    assertFalse(CODEC.encode(hello).containsKey("heldWorkspaces"), "absent on a first connection");

    String form = host.tokenBodies.getFirst();
    assertTrue(form.startsWith("grant_type=client_credentials"), form);
    assertTrue(form.contains("client_secret=client-secret"), form);
    assertTrue(host.tokenAuthorizations.stream().allMatch(a -> a == null), "never Basic");
    awaitTrue(() -> Files.exists(state.resolve("heartbeat")));
  }

  @Test
  void aLostSocketIsRedialledAndTheRunnerSaysHelloAgain() throws Exception {
    ackWith(1, 0);
    start("t");
    host.await(Hello.class, 1);

    host.socket.close();
    Hello again = host.await(Hello.class, 2);
    assertEquals(WorkspacesRunnerBinary.VERSION, again.runnerVersion());
    assertEquals(1, host.registerPaths.size(), "a redial is no registration");
  }

  // ---- the workspace frames this version answers -------------------------------------------------

  @Test
  void aTakeIsAnsweredLaunchFailedAtOnceStartsNothingAndHoldsNoSlot() throws Exception {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(1, 1));
            h.send(new Backlog(2));
          } else if (message instanceof Reserve && h.all(Reserve.class).size() <= 2) {
            h.send(new Take(h.all(Reserve.class).size(), spec()));
          }
        };
    start("t");

    // The second Reserve comes because the first take freed what it never really held: the
    // runner keeps asking, and every take it cannot launch is failed at once rather than stuck.
    LaunchFailed first = host.await(LaunchFailed.class, 1);
    assertEquals(1L, first.rowId());
    assertEquals(WorkspacesRunnerDomain.TAKE_UNSUPPORTED, first.detail());
    assertEquals(2L, host.await(LaunchFailed.class, 2).rowId());
    awaitTrue(() -> runner.session().held().isEmpty());
    assertTrue(docker.calls("run").isEmpty(), "nothing is started: " + docker.calls());
    assertTrue(docker.calls("pull").isEmpty(), "nothing is pulled: " + docker.calls());
  }

  @Test
  void stopAndDeleteOfAnAbsentRowAreAnsweredAndAPresentContainerIsLeftAlone() throws Exception {
    String absent = "qits-workspaces-runner-ws-7-r1";
    String present = "qits-workspaces-runner-ws-8-r1";
    docker.answerFor("inspect", absent, 1, "", "Error: No such object: " + absent);
    docker.answerFor("inspect", present, 0, "c8|running/none|2026-10-05T04:00:00Z\n", "");
    docker.container(present, Map.of("qits.workspaces.runner", RUNNER_ID));
    ackWith(1, 0);
    start("t");
    host.await(Hello.class);

    host.send(new Stop(7));
    host.send(new Delete(7));
    assertEquals(new Stopped(7), host.await(Stopped.class));
    assertEquals(new Deleted(7), host.await(Deleted.class));

    host.send(new Stop(8));
    host.send(new Delete(8));
    awaitCall(c -> c.stream().filter(call -> call.contains(present)).count() >= 2);
    Thread.sleep(300);
    assertEquals(1, host.all(Stopped.class).size(), "no answer that is not true");
    assertEquals(1, host.all(Deleted.class).size(), "no answer that is not true");
    assertTrue(docker.containers().containsKey(present));
    assertTrue(docker.calls("stop").isEmpty(), docker.calls()::toString);
    assertTrue(docker.calls("rm").isEmpty(), docker.calls()::toString);
  }

  @Test
  void aLoginProbeIsAnsweredUnknownUntilTheProbeExists() throws Exception {
    ackWith(1, 0);
    start("t");
    host.await(Hello.class);

    host.send(new ProbeLogin());
    assertEquals(
        new LoginState(LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, NOW.toString()),
        host.await(LoginState.class));
  }

  // ---- the health check ---------------------------------------------------------------------------

  @Test
  void theHealthCheckPullsTheWorkspaceImageRunsItUnderTheRunnersLabelAndRemovesIt()
      throws Exception {
    String name = "qits-workspaces-runner-health-check-r1";
    docker.answerFor("image-inspect", WORKSPACE_IMAGE, 1, "", "Error: No such image");
    docker.answerFor("inspect", name, 0, "abc|running/none|2026-10-05T04:38:19Z\n", "");
    estateThenAck();
    start("t");
    host.await(Hello.class);
    awaitTrue(() -> WORKSPACE_IMAGE.equals(domain.workspaceImage()));

    host.send(new HealthCheck());
    HealthChecked checked = host.await(HealthChecked.class);
    assertTrue(checked.ok(), checked.detail());

    List<List<String>> calls = docker.calls();
    List<String> pull =
        calls.stream().filter(c -> c.contains("pull")).findFirst().orElseThrow(() -> new AssertionError(calls));
    assertEquals("--config", pull.getFirst(), "pulled under the runner's own throwaway login");
    assertEquals(List.of("pull", WORKSPACE_IMAGE), FakeDocker.withoutConfig(pull));
    List<String> run = docker.calls("run").getFirst();
    assertEquals(
        List.of(
            "run",
            "-d",
            "--name",
            name,
            "--network",
            "bridge",
            "--label",
            "qits.workspaces.runner=r1",
            WORKSPACE_IMAGE),
        run,
        "no env, no mount, no restart policy, the runner's ownership label only");
    assertTrue(
        calls.indexOf(pull) < calls.indexOf(run), "pulled before it is run: " + calls);
    assertFalse(docker.containers().containsKey(name), "removed again");
  }

  @Test
  void aHealthCheckWhoseImageNeverRunsFailsAndStillRemovesItsContainer() throws Exception {
    String name = "qits-workspaces-runner-health-check-r1";
    docker.answerFor("inspect", name, 0, "abc|exited/none|2026-10-05T04:38:19Z\n", "");
    estateThenAck();
    start("t");
    awaitTrue(() -> WORKSPACE_IMAGE.equals(domain.workspaceImage()));

    host.send(new HealthCheck());
    HealthChecked checked = host.await(HealthChecked.class);
    assertFalse(checked.ok());
    assertTrue(checked.detail().contains("did not run"), checked.detail());
    assertTrue(checked.detail().contains("exited"), checked.detail());
    assertTrue(docker.calls("pull").isEmpty(), "an image already here is not pulled again");
    assertFalse(docker.containers().containsKey(name));
  }

  @Test
  void aHealthCheckBeforeAnyEstateFailsSayingWhy() throws Exception {
    ackWith(1, 0);
    start("t");
    host.await(Hello.class);

    host.send(new HealthCheck());
    HealthChecked checked = host.await(HealthChecked.class);
    assertFalse(checked.ok());
    assertTrue(checked.detail().contains("no estate"), checked.detail());
    assertTrue(docker.calls("run").isEmpty());
  }

  // ---- rollover ------------------------------------------------------------------------------------

  @Test
  void anUpgradeWhileTwoWorkspacesRunStartsTheSuccessorAtOnceAndRemovesNeither() throws Exception {
    runnerContainer();
    docker.container(
        "qits-workspaces-runner-ws-1-r1",
        Map.of("qits.workspaces.runner", RUNNER_ID, "qits.workspaces.runner.workspace", "1"));
    docker.container(
        "qits-workspaces-runner-ws-2-r1",
        Map.of("qits.workspaces.runner", RUNNER_ID, "qits.workspaces.runner.workspace", "2"));
    estateThenAck();
    start("t");
    host.await(Hello.class);
    awaitTrue(() -> WORKSPACE_IMAGE.equals(domain.workspaceImage()));

    host.send(new Upgrade(NEXT, IMAGE, DIGEST));
    awaitCall(c -> c.contains(SUCCESSOR_RUN));

    host.send(new Retire("superseded by " + NEXT));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertTrue(docker.containers().containsKey("qits-workspaces-runner-ws-1-r1"));
    assertTrue(docker.containers().containsKey("qits-workspaces-runner-ws-2-r1"));
    for (List<String> call : docker.calls()) {
      assertFalse(
          call.stream().anyMatch(a -> a.startsWith("qits-workspaces-runner-ws-")),
          "a workspace container was touched: " + call);
    }
    assertTrue(
        has(docker.calls(), List.of("update", "--restart=no", SELF)), docker.calls()::toString);
  }

  @Test
  void theSuccessorWaitsOnlyForAStopInFlight() throws Exception {
    runnerContainer();
    String name = "qits-workspaces-runner-ws-7-r1";
    docker.answerFor("inspect", name, 1, "", "Error: No such object: " + name);
    docker.hang("inspect+" + name, 3);
    ackWith(1, 0);
    start("t");
    host.await(Hello.class);

    host.send(new Stop(7));
    awaitCall(c -> c.stream().anyMatch(call -> call.get(0).equals("inspect") && call.contains(name)));
    host.send(new Upgrade(NEXT, IMAGE, DIGEST));
    awaitCall(c -> has(c, List.of("image", "inspect", "--format", "{{json .RepoDigests}}", IMAGE)));
    // Pulled and checked; a quiescent runner would start the successor within one 50 ms poll.
    Thread.sleep(800);
    assertFalse(docker.calls().contains(SUCCESSOR_RUN), "no successor while a stop is in flight");
    assertTrue(host.all(Stopped.class).isEmpty(), "the stop is still in flight");

    host.await(Stopped.class);
    awaitCall(c -> c.contains(SUCCESSOR_RUN));
  }

  // ---- decommission --------------------------------------------------------------------------------

  @Test
  void decommissionRemovesEveryLabelledObjectAndNothingElse() throws Exception {
    containerWithStateVolume();
    Map<String, String> ws1 =
        Map.of("qits.workspaces.runner", RUNNER_ID, "qits.workspaces.runner.workspace", "1");
    docker.container("qits-workspaces-runner-ws-1-r1", ws1);
    docker.volume("qits-workspaces-runner-ws-1-r1", ws1);
    docker.volume("qits-workspaces-runner-dot-claude-r1", Map.of("qits.workspaces.runner", RUNNER_ID));
    docker.volume("qits-workspaces-runner-m2-r1", Map.of("qits.workspaces.runner", RUNNER_ID));
    docker.volume(IDENTITY.stateVolume(RUNNER_ID), Map.of());
    // The node's other tenants: a CI runner's step container and a person's own volume.
    docker.container("qits-ci-runner-x", Map.of("qits.ci.runner", RUNNER_ID));
    docker.volume("my-data", Map.of());
    ackWith(1, 0);
    start("t");
    host.await(Hello.class);

    host.send(Retire.deleted("deleted by admin"));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertFalse(Files.exists(state.resolve("client.json")), "the revoked client is forgotten");
    List<String> helperRun =
        docker.calls("run").stream()
            .filter(c -> c.contains(IDENTITY.helperName(RUNNER_ID)))
            .findFirst()
            .orElseThrow(() -> new AssertionError(docker.calls()));
    assertFalse(
        helperRun.stream().anyMatch(a -> a.contains("DECOMMISSION_TEARDOWN")),
        "no teardown item: everything the runner makes carries its label");

    // The runner's container, exited now, as the helper finds it on the node.
    String process = IDENTITY.containerName(RUNNER_ID, WorkspacesRunnerBinary.VERSION);
    docker.container(process, Map.of(IDENTITY.processLabel(), RUNNER_ID));
    Map<String, String> env = new HashMap<>();
    for (int i = 0; i < helperRun.size() - 1; i++) {
      if (helperRun.get(i).equals("-e")) {
        String[] kv = helperRun.get(i + 1).split("=", 2);
        env.put(kv[0], kv[1]);
      }
    }
    env.put(IDENTITY.env(RunnerSettings.DOCKER_BINARY), docker.binaryFor("helper"));
    docker.answer("inspect", 0, "exited\n", "");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int code =
        RunnerLauncher.main(
            IDENTITY,
            WorkspacesRunnerDomain::new,
            vertx,
            new String[0],
            env::get,
            new PrintStream(out, true, StandardCharsets.UTF_8));
    String said = out.toString(StandardCharsets.UTF_8);
    assertEquals(0, code, said);

    assertEquals(
        Set.of(IDENTITY.helperName(RUNNER_ID), "qits-ci-runner-x"),
        docker.containers().keySet(),
        said);
    assertEquals(Set.of("my-data"), docker.volumes().keySet(), said);
  }

  @Test
  void aRunnerDeletedCloseDecommissionsAtOnce() throws Exception {
    ackWith(1, 0);
    start("t");
    host.await(Hello.class);

    host.closeSocket(RunnerWire.CLOSE_POLICY_VIOLATION, RunnerWire.CloseReason.RUNNER_DELETED);
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertFalse(Files.exists(state.resolve("client.json")));
    assertEquals(1, host.upgrades.get(), "no redial");
  }

  // ---- health, the version probe and a missing environment -----------------------------------------

  @Test
  void healthExitsOneBeforeTheFirstAckAndZeroAfterIt() throws Exception {
    host.script = (h, message) -> {};
    start("t");
    host.await(Hello.class);
    assertEquals(1, health(), "a Hello the host never answered is not admission");

    host.send(new Ack(1, 1));
    awaitTrue(() -> health() == 0);
  }

  @Test
  void printVersionPrintsTheVersionAndNoEnvironmentExitsTwo() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int code =
        RunnerLauncher.main(
            IDENTITY,
            WorkspacesRunnerDomain::new,
            vertx,
            new String[0],
            Map.of("QITS_WORKSPACES_RUNNER_PRINT_VERSION", "1")::get,
            new PrintStream(out, true, StandardCharsets.UTF_8));
    assertEquals(0, code);
    assertEquals(WorkspacesRunnerBinary.VERSION + "\n", out.toString(StandardCharsets.UTF_8));

    int bare =
        RunnerLauncher.main(
            IDENTITY,
            WorkspacesRunnerDomain::new,
            vertx,
            new String[0],
            name -> null,
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    assertEquals(RunnerExit.MISCONFIGURED, bare);
  }

  // ---- the runner, the host's scripts and the node -------------------------------------------------

  private WorkspacesRunnerDomain domain;

  private Map<String, String> env(String token) {
    Map<String, String> env = new HashMap<>();
    env.put(IDENTITY.env(RunnerSettings.URL), host.base());
    env.put(IDENTITY.env(RunnerSettings.ID), RUNNER_ID);
    env.put(IDENTITY.env(RunnerSettings.REGISTRATION_TOKEN), token);
    env.put(IDENTITY.env(RunnerSettings.STATE_DIR), state.toString());
    env.put(IDENTITY.env(RunnerSettings.SLOTS), "2");
    env.put(IDENTITY.env(RunnerSettings.DOCKER_BINARY), docker.binary());
    env.put(IDENTITY.env(RunnerSettings.DOCKER_TIMEOUT), "10");
    return env;
  }

  private void start(String token) throws Exception {
    RunnerSettings settings =
        RunnerSettings.parse(IDENTITY, env(token)::get)
            .withTiming(new RunnerSettings.Timing(10_000, 50, 200, 5_000, 300_000, 5));
    domain =
        new WorkspacesRunnerDomain(
            settings,
            Duration.ofMillis(600),
            Duration.ofMillis(50),
            Clock.fixed(NOW, ZoneOffset.UTC));
    runner =
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
                Housekeeping.Settings.defaults(),
                Clock.systemUTC()));
    exit = CompletableFuture.supplyAsync(runner::run);
  }

  private void stopRunner() throws Exception {
    if (runner != null) {
      runner.stop(RunnerExit.OK);
      exit.get(10, TimeUnit.SECONDS);
      runner = null;
    }
  }

  private void ackWith(int slots, int backlog) {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), slots));
            h.send(new Backlog(backlog));
          }
        };
  }

  /** Ack every Hello, then the estate the server sends right after it. */
  private void estateThenAck() {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), 1));
            h.send(new Estate(List.of(1L, 2L), WORKSPACE_IMAGE));
          }
        };
  }

  private static WorkspaceSpec spec() {
    return new WorkspaceSpec(
        WORKSPACE_IMAGE, Map.of(), List.of(), Map.of(), null, null, null, null, null, true);
  }

  private int health() {
    return RunnerLauncher.main(
        IDENTITY,
        WorkspacesRunnerDomain::new,
        vertx,
        new String[] {"health"},
        Map.of(IDENTITY.env(RunnerSettings.STATE_DIR), state.toString())::get,
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
  }

  private void awaitCall(Predicate<List<List<String>>> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.test(docker.calls()) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.test(docker.calls()), () -> "docker calls: " + docker.calls());
  }

  private static boolean has(List<List<String>> calls, List<String> call) {
    return calls.stream().map(FakeDocker::withoutConfig).anyMatch(call::equals);
  }

  private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "the condition never held");
  }

  // ---- the node as docker answers it ---------------------------------------------------------------

  private static final String NEXT = "2026.999.1";
  private static final String IMAGE = "registry.example:5000/qits/qits-workspaces-runner:" + NEXT;
  private static final String DIGEST =
      "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private static final List<String> SUCCESSOR_RUN =
      List.of(
          "run",
          "-d",
          "--name",
          "qits-workspaces-runner-r1-" + NEXT,
          "--restart",
          "unless-stopped",
          "--label",
          "qits.workspaces.runner.process=r1",
          "--label",
          "qits.workspaces.runner.version=" + NEXT,
          "-v",
          "/var/run/docker.sock:/var/run/docker.sock",
          "-v",
          "qits-workspaces-runner-state-r1:/var/lib/qits-workspaces-runner",
          "-e",
          "QITS_WORKSPACES_RUNNER_ID=r1",
          "-e",
          "QITS_WORKSPACES_RUNNER_SLOTS=2",
          "-e",
          "QITS_WORKSPACES_RUNNER_URL=https://workspaces.qits.example.eu",
          IMAGE);

  /**
   * The runner's own container as {@code docker inspect} answers it for a successor (what the
   * install script started), its old image's config, and the pulled image's digest.
   */
  private void runnerContainer() throws Exception {
    docker.answerFor(
        "inspect",
        SELF,
        0,
        new JsonArray()
                .add(
                    new JsonObject()
                        .put("Id", SELF + "0".repeat(52))
                        .put("Image", "sha256:old")
                        .put(
                            "Config",
                            new JsonObject()
                                .put(
                                    "Env",
                                    List.of(
                                        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                                        "QITS_WORKSPACES_RUNNER_URL=https://workspaces.qits.example.eu",
                                        "QITS_WORKSPACES_RUNNER_ID=r1",
                                        "QITS_WORKSPACES_RUNNER_SLOTS=2",
                                        "QITS_WORKSPACES_RUNNER_REGISTRATION_TOKEN=qits_tok_SPENT"))
                                .put(
                                    "Labels",
                                    Map.of(
                                        "qits.workspaces.runner.process", "r1",
                                        "qits.workspaces.runner.version",
                                            WorkspacesRunnerBinary.VERSION)))
                        .put(
                            "HostConfig",
                            new JsonObject()
                                .put(
                                    "Binds",
                                    List.of(
                                        "/var/run/docker.sock:/var/run/docker.sock",
                                        "qits-workspaces-runner-state-r1:/var/lib/qits-workspaces-runner"))
                                .put(
                                    "RestartPolicy",
                                    Map.of("Name", "unless-stopped", "MaximumRetryCount", 0))
                                .put("NetworkMode", "default")))
                .encode()
            + "\n",
        "");
    docker.answerFor(
        "image-inspect",
        "sha256:old",
        0,
        new JsonArray()
                .add(
                    new JsonObject()
                        .put("Id", "sha256:old")
                        .put(
                            "Config",
                            new JsonObject()
                                .put(
                                    "Env",
                                    List.of(
                                        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"))))
                .encode()
            + "\n",
        "");
    docker.answerFor(
        "image-inspect",
        IMAGE,
        0,
        "[\"registry.example:5000/qits/qits-workspaces-runner@" + DIGEST + "\"]\n",
        "");
  }

  /** The runner's own container with its state volume mounted at this test's state directory. */
  private void containerWithStateVolume() throws Exception {
    docker.answerFor(
        "inspect",
        SELF,
        0,
        new JsonArray()
                .add(
                    new JsonObject()
                        .put("Id", SELF + "0".repeat(52))
                        .put("Image", "sha256:old")
                        .put(
                            "Mounts",
                            new JsonArray()
                                .add(
                                    new JsonObject()
                                        .put("Type", "bind")
                                        .put("Source", "/var/run/docker.sock")
                                        .put("Destination", "/var/run/docker.sock"))
                                .add(
                                    new JsonObject()
                                        .put("Type", "volume")
                                        .put("Name", "qits-workspaces-runner-state-r1")
                                        .put("Destination", state.toString())))
                        .put(
                            "HostConfig",
                            new JsonObject()
                                .put("RestartPolicy", Map.of("Name", "unless-stopped"))))
                .encode()
            + "\n",
        "");
  }
}
