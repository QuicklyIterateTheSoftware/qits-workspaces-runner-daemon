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
import eu.wohlben.qits.runner.protocol.RunnerMessage;
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
import eu.wohlben.qits.workspacesrunner.protocol.Exited;
import eu.wohlben.qits.workspacesrunner.protocol.HealthCheck;
import eu.wohlben.qits.workspacesrunner.protocol.HealthChecked;
import eu.wohlben.qits.workspacesrunner.protocol.HeldContainer;
import eu.wohlben.qits.workspacesrunner.protocol.Inventory;
import eu.wohlben.qits.workspacesrunner.protocol.LaunchFailed;
import eu.wohlben.qits.workspacesrunner.protocol.Launched;
import eu.wohlben.qits.workspacesrunner.protocol.LoginPresence;
import eu.wohlben.qits.workspacesrunner.protocol.LoginState;
import eu.wohlben.qits.workspacesrunner.protocol.Mount;
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
import java.util.LinkedHashMap;
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
    if (domain != null) {
      domain.close();
    }
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

  // ---- take: the three rungs ----------------------------------------------------------------------

  @Test
  void aTakeOfANewRowPullsMakesItsVolumesAndRunsItUnderTheRunnersOwnLabels() throws Exception {
    docker.answerFor("image-inspect", WORKSPACE_IMAGE, 1, "", "Error: No such image");
    takeOnFirstReserve(5, fullSpec());
    start("t");

    assertEquals(new Launched(5, ws(5)), host.await(Launched.class));
    String hash = WorkspaceLauncher.specHash(fullSpec());
    List<String> run = runOf(ws(5));
    assertEquals(
        List.of(
            "run",
            "-d",
            "--init",
            "--name",
            ws(5),
            "--network",
            "bridge",
            "--user",
            "1000",
            "--label",
            "qits.repository=qits-qits",
            "--label",
            "qits.workspace=w5",
            "--label",
            "qits.workspaces.runner=r1",
            "--label",
            "qits.workspaces.runner.spec=" + hash,
            "--label",
            "qits.workspaces.runner.workspace=5",
            "--memory",
            "4g",
            "--memory-swap",
            "4g",
            "--pids-limit",
            "512",
            "--cpus",
            "2",
            "--oom-score-adj",
            "500",
            "-v",
            ws(5) + ":/workspace",
            "-v",
            "qits-workspaces-runner-dot-claude-r1:/claude-home",
            "-v",
            "qits-workspaces-runner-m2-r1:/caches/m2",
            "-v",
            "qits-workspaces-runner-pnpm-r1:/caches/pnpm",
            "-e",
            "QITS_WORKSPACE_DAEMON_WORKSPACE_ID=w5",
            "-e",
            "TZ=UTC",
            WORKSPACE_IMAGE),
        run,
        "no --restart (docker's `no`), the default bridge, uid 1000, the runner's labels");
    List<List<String>> calls = docker.calls().stream().map(FakeDocker::withoutConfig).toList();
    assertTrue(
        calls.indexOf(List.of("pull", WORKSPACE_IMAGE)) >= 0
            && calls.indexOf(List.of("pull", WORKSPACE_IMAGE)) < calls.indexOf(run),
        "pulled under the runner's login before it is run: " + calls);
    assertEquals(
        Map.of("qits.workspaces.runner", RUNNER_ID, "qits.workspaces.runner.workspace", "5"),
        docker.volumes().get(ws(5)));
    for (String node :
        List.of(
            "qits-workspaces-runner-dot-claude-r1",
            "qits-workspaces-runner-m2-r1",
            "qits-workspaces-runner-pnpm-r1")) {
      assertEquals(Map.of("qits.workspaces.runner", RUNNER_ID), docker.volumes().get(node), node);
    }
    awaitTrue(() -> runner.session().held().equals(List.of("5")));
    awaitTrue(
        () -> {
          List<RunnerMessage> received = List.copyOf(host.received);
          int launched = indexOf(received, Launched.class, 0);
          return launched >= 0 && indexOf(received, Inventory.class, launched) > launched;
        });
  }

  @Test
  void aRunningContainerOfTheRowIsLaunchedAsItIs() throws Exception {
    seen(5, "running", 0, "any");
    takeOnFirstReserve(5, fullSpec());
    start("t");

    assertEquals(new Launched(5, "c5"), host.await(Launched.class));
    assertTrue(runsOf(ws(5)).isEmpty(), docker.calls()::toString);
    assertFalse(touched("start", ws(5)) || touched("rm", ws(5)), docker.calls()::toString);
  }

  @Test
  void aStoppedContainerOfTheSameSpecIsStartedAgain() throws Exception {
    seen(5, "exited", 0, WorkspaceLauncher.specHash(fullSpec()));
    takeOnFirstReserve(5, fullSpec());
    start("t");

    assertEquals(new Launched(5, "c5"), host.await(Launched.class));
    assertTrue(has(docker.calls(), List.of("start", ws(5))), docker.calls()::toString);
    assertTrue(runsOf(ws(5)).isEmpty(), "the same container, not a second one");
    assertFalse(touched("rm", ws(5)), docker.calls()::toString);
  }

  @Test
  void aContainerOfAnotherSpecIsRecreatedAndItsVolumeKept() throws Exception {
    seen(5, "exited", 137, "an-older-spec");
    Map<String, String> labels =
        Map.of("qits.workspaces.runner", RUNNER_ID, "qits.workspaces.runner.workspace", "5");
    docker.volume(ws(5), labels);
    takeOnFirstReserve(5, fullSpec());
    start("t");

    host.await(Launched.class);
    List<List<String>> calls = docker.calls();
    int removed = calls.indexOf(List.of("rm", "-f", ws(5)));
    assertTrue(removed >= 0, calls::toString);
    assertTrue(removed < calls.indexOf(runOf(ws(5))), "removed, then created: " + calls);
    assertTrue(
        runOf(ws(5)).contains("qits.workspaces.runner.spec=" + WorkspaceLauncher.specHash(fullSpec())));
    assertEquals(labels, docker.volumes().get(ws(5)), "the volume is the work; it stays");
    assertTrue(docker.calls("volume").stream().noneMatch(c -> c.contains("rm")), calls::toString);
  }

  @Test
  void aTakeDockerRefusesIsLaunchFailedWithDockersLastWordsAndHoldsNoSlot() throws Exception {
    docker.answerFor(
        "run", WORKSPACE_IMAGE, 125, "", "docker: Error response from daemon: no space left on device.");
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(1, 1));
            h.send(new Backlog(2));
          } else if (message instanceof Reserve && h.all(Reserve.class).size() <= 2) {
            h.send(new Take(h.all(Reserve.class).size(), fullSpec()));
          }
        };
    start("t");

    LaunchFailed first = host.await(LaunchFailed.class, 1);
    assertEquals(1L, first.rowId());
    assertTrue(first.detail().contains("no space left on device"), first.detail());
    // The second Reserve proves the refused take was answered to the ledger (taken, released).
    assertEquals(2L, host.await(LaunchFailed.class, 2).rowId());
    awaitTrue(() -> runner.session().held().isEmpty());
  }

  // ---- stop, delete, exited and the slots ----------------------------------------------------------

  @Test
  void stopStopsTheContainerAnswersStoppedAndFreesTheSlotAndAnAbsentRowIsStoppedToo()
      throws Exception {
    seen(5, "running", 0, "h5");
    ackWith(1, 0);
    start("t");
    awaitTrue(() -> runner.session().held().equals(List.of("5")));

    host.send(new Stop(5));
    assertEquals(new Stopped(5), host.await(Stopped.class));
    assertTrue(has(docker.calls(), List.of("stop", ws(5))), docker.calls()::toString);
    awaitTrue(() -> runner.session().held().isEmpty());
    assertTrue(docker.containers().containsKey(ws(5)), "stopped, not removed");

    host.send(new Stop(9));
    assertEquals(new Stopped(9), host.await(Stopped.class, 2));
    assertFalse(touched("stop", ws(9)), "nothing to stop");
  }

  @Test
  void deleteRemovesTheContainerAndItsVolumeOnlyAndAnAbsentRowIsDeletedToo() throws Exception {
    seen(5, "exited", 0, "h5");
    docker.volume(
        ws(5), Map.of("qits.workspaces.runner", RUNNER_ID, "qits.workspaces.runner.workspace", "5"));
    ackWith(1, 0);
    start("t");
    host.await(Inventory.class);

    host.send(new Delete(5));
    assertEquals(new Deleted(5), host.await(Deleted.class));
    assertFalse(docker.containers().containsKey(ws(5)));
    assertFalse(docker.volumes().containsKey(ws(5)));
    assertTrue(
        docker.volumes().containsKey("qits-workspaces-runner-dot-claude-r1"),
        "the node's agent home is no row's");

    host.send(new Delete(9));
    assertEquals(new Deleted(9), host.await(Deleted.class, 2));
  }

  @Test
  void runningContainersHoldTheirSlotsFromTheSessionStartAndAnExitFreesOne() throws Exception {
    seen(1, "running", 0, "h1");
    seen(2, "running", 0, "h2");
    ackWith(2, 1);
    start("t");
    host.await(Hello.class);
    awaitTrue(() -> runner.session().held().equals(List.of("1", "2")));
    Thread.sleep(500);
    assertTrue(host.all(Reserve.class).isEmpty(), "two running workspaces fill two slots");

    seen(2, "exited", 137, "h2");
    assertEquals(new Exited(2, 137), host.await(Exited.class));
    host.await(Reserve.class);
    assertEquals(List.of("1"), runner.session().held());
    awaitTrue(
        () ->
            host.all(Inventory.class).getLast().containers()
                .equals(
                    List.of(new HeldContainer(1, true, "h1"), new HeldContainer(2, false, "h2"))));
    assertEquals(1, host.all(Exited.class).size(), "an exit is said once");
  }

  // ---- the estate ----------------------------------------------------------------------------------

  @Test
  void theEstateRemovesOnlyUnownedLabelledObjectsAndNothingBeforeIt() throws Exception {
    housekeepingMillis = 200;
    probesAnswer();
    seen(1, "running", 0, "h1");
    seen(3, "exited", 0, "h3");
    Map<String, String> row1 =
        Map.of("qits.workspaces.runner", RUNNER_ID, "qits.workspaces.runner.workspace", "1");
    Map<String, String> row3 =
        Map.of("qits.workspaces.runner", RUNNER_ID, "qits.workspaces.runner.workspace", "3");
    Map<String, String> otherRunners =
        Map.of("qits.workspaces.runner", "r2", "qits.workspaces.runner.workspace", "4");
    docker.volume(ws(1), row1);
    docker.volume(ws(3), row3);
    // The node's other tenants: names like ours, without our label.
    docker.container("qits-workspaces-runner-ws-3-r1-copy", Map.of());
    docker.container("qits-workspaces-runner-ws-4-r2", otherRunners);
    docker.volume("qits-workspaces-runner-ws-9-r1", Map.of());
    docker.volume("qits-workspaces-runner-ws-4-r2", otherRunners);
    ackWith(1, 0);
    start("t");
    host.await(Inventory.class);

    // Several housekeeping passes and exit watches, and no estate: nothing goes.
    Thread.sleep(1_000);
    Set<String> containersBefore =
        Set.of(ws(1), ws(3), "qits-workspaces-runner-ws-3-r1-copy", "qits-workspaces-runner-ws-4-r2");
    assertEquals(containersBefore, docker.containers().keySet());
    assertTrue(docker.volumes().keySet().containsAll(Set.of(ws(1), ws(3))));
    assertTrue(docker.calls("rm").isEmpty(), docker.calls()::toString);

    host.send(new Estate(List.of(1L), WORKSPACE_IMAGE));
    awaitTrue(() -> !docker.containers().containsKey(ws(3)));
    awaitTrue(() -> !docker.volumes().containsKey(ws(3)));

    assertEquals(
        Set.of(ws(1), "qits-workspaces-runner-ws-3-r1-copy", "qits-workspaces-runner-ws-4-r2"),
        docker.containers().keySet());
    assertEquals(
        Set.of(
            ws(1),
            "qits-workspaces-runner-dot-claude-r1",
            "qits-workspaces-runner-ws-9-r1",
            "qits-workspaces-runner-ws-4-r2"),
        docker.volumes().keySet());
    awaitTrue(
        () ->
            host.all(Inventory.class).stream()
                .anyMatch(
                    i ->
                        i.containers().equals(List.of(new HeldContainer(1, true, "h1")))
                            && "qits-workspaces-runner-dot-claude-r1".equals(i.dotClaudeVolume())));
  }

  @Test
  void everyEstatePullsTheWorkspaceImageOnlyWhenItIsNotHere() throws Exception {
    probesAnswer();
    estateThenAck();
    start("t");
    host.await(LoginState.class);
    assertTrue(docker.calls("pull").isEmpty(), "here already: " + docker.calls());

    docker.answerFor("image-inspect", WORKSPACE_IMAGE, 1, "", "Error: No such image");
    host.send(new Estate(List.of(1L, 2L), WORKSPACE_IMAGE));
    host.await(LoginState.class, 2);
    List<String> pull =
        docker.calls().stream()
            .filter(c -> c.contains("pull"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(docker.calls()));
    assertEquals("--config", pull.getFirst(), "pulled under the runner's own throwaway login");
    assertEquals(List.of("pull", WORKSPACE_IMAGE), FakeDocker.withoutConfig(pull));
  }

  // ---- the login ----------------------------------------------------------------------------------

  @Test
  void theLoginIsProbedAfterTheEstateEveryFewMinutesWhileAbsentAndHourlyOnceThere()
      throws Exception {
    timing =
        new WorkspacesRunnerDomain.Timing(
            Duration.ofMillis(600),
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            Duration.ofMillis(150),
            Duration.ofHours(1));
    docker.answerFor("run", "status", 1, "{\"loggedIn\": false}\n", "");
    docker.answerFor("run", LoginProbe.KIMI_CHECK, 1, "", "");
    estateThenAck();
    start("t");

    host.await(LoginState.class, 3);
    assertTrue(
        host.all(LoginState.class).stream()
            .allMatch(
                l ->
                    l.claude() == LoginPresence.ABSENT
                        && l.kimi() == LoginPresence.ABSENT
                        && l.checkedAt().equals(NOW.toString())));

    // The operator logs in on the node.
    docker.answerFor("run", "status", 0, "{\"loggedIn\": true}\n", "");
    docker.answerFor("run", LoginProbe.KIMI_CHECK, 0, "", "");
    awaitTrue(
        () -> {
          LoginState last = host.all(LoginState.class).getLast();
          return last.claude() == LoginPresence.PRESENT && last.kimi() == LoginPresence.PRESENT;
        });
    int settled = host.all(LoginState.class).size();
    Thread.sleep(800);
    assertEquals(settled, host.all(LoginState.class).size(), "present: the next probe is an hour off");

    host.send(new ProbeLogin());
    LoginState rechecked = host.await(LoginState.class, settled + 1);
    assertEquals(LoginPresence.PRESENT, rechecked.claude());
  }

  @Test
  void aLoginProbeBeforeAnyEstateIsUnknown() throws Exception {
    ackWith(1, 0);
    start("t");
    host.await(Hello.class);

    host.send(new ProbeLogin());
    assertEquals(
        new LoginState(LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, NOW.toString()),
        host.await(LoginState.class));
    assertTrue(docker.calls("run").isEmpty(), "no image to probe with");
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
    List<String> run = runOf(name);
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
    for (String verb : List.of("stop", "rm", "start", "update")) {
      assertFalse(touched(verb, "qits-workspaces-runner-ws-1-r1"), docker.calls()::toString);
      assertFalse(touched(verb, "qits-workspaces-runner-ws-2-r1"), docker.calls()::toString);
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
    NodeVolumes nodeVolumes = new NodeVolumes(IDENTITY, RUNNER_ID);
    docker.volume(nodeVolumes.m2(), Map.of("qits.workspaces.runner", RUNNER_ID));
    docker.volume(nodeVolumes.pnpm(), Map.of("qits.workspaces.runner", RUNNER_ID));
    docker.volume(IDENTITY.stateVolume(RUNNER_ID), Map.of());
    // The node's other tenants: a CI runner's step container and a person's own volume.
    docker.container("qits-ci-runner-x", Map.of("qits.ci.runner", RUNNER_ID));
    docker.volume("my-data", Map.of());
    ackWith(1, 0);
    start("t");
    host.await(Hello.class);
    // The agent home — and the login on it — is the runner's own, made labelled at its Ack.
    awaitTrue(
        () ->
            Map.of("qits.workspaces.runner", RUNNER_ID)
                .equals(docker.volumes().get(nodeVolumes.dotClaude())));

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

  /** The node's clocks: the health check's, a 100 ms exit watch, and the login cadence. */
  private WorkspacesRunnerDomain.Timing timing =
      new WorkspacesRunnerDomain.Timing(
          Duration.ofMillis(600),
          Duration.ofMillis(50),
          Duration.ofMillis(100),
          Duration.ofSeconds(60),
          Duration.ofSeconds(60));

  private long housekeepingMillis = TimeUnit.HOURS.toMillis(6);

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
    if (domain != null) {
      domain.close();
    }
    domain = new WorkspacesRunnerDomain(settings, timing, Clock.fixed(NOW, ZoneOffset.UTC));
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
                new Housekeeping.Settings(housekeepingMillis),
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

  /** Answer the first Reserve with a take of {@code rowId}. */
  private void takeOnFirstReserve(long rowId, WorkspaceSpec spec) {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), 2));
            h.send(new Backlog(1));
          } else if (message instanceof Reserve && h.all(Reserve.class).size() == 1) {
            h.send(new Take(rowId, spec));
          }
        };
  }

  /** What qits-workspaces composes for a runner workspace: no address, no credential. */
  private static WorkspaceSpec fullSpec() {
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

  /** {@code qits-workspaces-runner-ws-<rowId>-r1}: a row's container and volume. */
  private static String ws(long rowId) {
    return "qits-workspaces-runner-ws-" + rowId + "-r1";
  }

  /** The row's container on the node, as docker's inspect answers it from now on. */
  private void seen(long rowId, String status, int exitCode, String specHash) throws Exception {
    docker.container(
        ws(rowId),
        Map.of(
            "qits.workspaces.runner",
            RUNNER_ID,
            "qits.workspaces.runner.workspace",
            Long.toString(rowId)));
    docker.answerFor(
        "inspect",
        ws(rowId),
        0,
        "c" + rowId + "|" + status + "|" + exitCode + "|" + rowId + "|" + specHash + "\n",
        "");
  }

  /** Both login probes answered present, so no probe leaves a container in the fake's state. */
  private void probesAnswer() throws Exception {
    docker.answerFor("run", "status", 0, "{\"loggedIn\": true}\n", "");
    docker.answerFor("run", LoginProbe.KIMI_CHECK, 0, "", "");
  }

  private List<List<String>> runsOf(String name) {
    return docker.calls("run").stream().filter(c -> c.contains(name)).toList();
  }

  private List<String> runOf(String name) {
    List<List<String>> runs = runsOf(name);
    assertFalse(runs.isEmpty(), () -> "no run of " + name + ": " + docker.calls());
    return runs.getFirst();
  }

  private boolean touched(String verb, String name) {
    return docker.calls(verb).stream().anyMatch(c -> c.contains(name));
  }

  private static int indexOf(List<RunnerMessage> received, Class<?> type, int from) {
    for (int i = from; i < received.size(); i++) {
      if (type.isInstance(received.get(i))) {
        return i;
      }
    }
    return -1;
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
