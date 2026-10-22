package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.toolkit.RunnerExit;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import eu.wohlben.qits.workspacesrunner.RunnerProviderStates.Node;
import eu.wohlben.qits.workspacesrunner.contracts.MessageContract;
import eu.wohlben.qits.workspacesrunner.contracts.MessageContract.Row;
import eu.wohlben.qits.workspacesrunner.contracts.MessageGoldenMasters;
import eu.wohlben.qits.workspacesrunner.contracts.MessageGoldenMasters.Trigger;
import eu.wohlben.qits.workspacesrunner.contracts.PactMessage;
import eu.wohlben.qits.workspacesrunner.protocol.Deleted;
import eu.wohlben.qits.workspacesrunner.protocol.Estate;
import eu.wohlben.qits.workspacesrunner.protocol.HealthChecked;
import eu.wohlben.qits.workspacesrunner.protocol.HeldContainer;
import eu.wohlben.qits.workspacesrunner.protocol.Inventory;
import eu.wohlben.qits.workspacesrunner.protocol.Launched;
import eu.wohlben.qits.workspacesrunner.protocol.LoginPresence;
import eu.wohlben.qits.workspacesrunner.protocol.LoginState;
import eu.wohlben.qits.workspacesrunner.protocol.Stopped;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>What qits-workspaces-service tells a workspace runner, and what the runner does with it</b>
 * (qits-950, A1): one row per frame the service records in a service-side state, built from its
 * pinned golden masters ({@code qits.workspaces-golden-masters.version}) into the message pact
 * {@code pacts/qits-workspaces-runner-daemon_qits-workspaces-service.json} ({@code
 * ServiceFramesConsumerPactTest} writes it; the service verifies it).
 *
 * <p><b>Each row is the frame fed to the real runner</b>: {@link WorkspacesRunnerDomain} under
 * qits-runner-toolkit's real {@code RunnerRuntime}, a {@link FakeDocker} node and a {@code
 * FakeRunnerHost} — {@link RunnerProviderStates.Node}, the provider states' own — with the pact's
 * message text written onto the socket verbatim ({@code sendRaw}), decoded by the runner's own
 * codec and dispatched by the toolkit. The row asserts the effect on the node and the frames the
 * runner answers with. The setting up (the hello greeted, a stop's workspace running) is the host's
 * side and uses typed frames; the frame under contract never is.
 *
 * <p><b>The service's frozen values become the row's own</b> ({@link PactMessage#text(Map)}): the
 * service records workspace 1, and every row holds workspace {@link #OWN} instead, swapped in at
 * the paths the service froze. A health check's request id is set to a fresh one. Every text fed is
 * first matched against the interaction's rules.
 *
 * <p><b>The trigger is the service's</b>: what on its side made it send the frame — a socket event
 * ({@code workspaces-runner:hello}, {@code workspaces-runner:reserve}) or an operation of its API
 * ({@code stopWorkspaceContainer}, {@code deleteRunner}, …), as its index records it.
 */
final class ServiceFramesContract {

  /** The consumer, as the pact names it: this repository. */
  static final String CONSUMER = "qits-workspaces-runner-daemon";

  static final MessageGoldenMasters.Provider SERVICE =
      new MessageGoldenMasters.Provider("qits-workspaces-service", "qits-workspaces");

  static final MessageGoldenMasters MASTERS = MessageGoldenMasters.of(SERVICE);

  // qits-workspaces-service's states (golden masters 2026.1022.75919).
  static final String HOLDING_ITS_WORKSPACE =
      "a greenlit runner dialling in holding a workspace it owns";
  static final String QUEUED_FOR_IT =
      "a greenlit runner dialling in with a workspace queued for it";
  static final String OLDER_VERSION = "a greenlit runner dialling in at an older version";
  static final String QUARANTINED_DIALLING_IN = "a quarantined runner dialling in";
  static final String QUARANTINED_ON_A_SOCKET = "a quarantined runner holding a socket";
  static final String IN_SERVICE = "a runner in service";
  static final String FREE_SLOT = "a runner in service with a free slot and a queued workspace";
  static final String SLOTS_TAKEN = "a runner in service with every slot taken";
  static final String SLOT_RUNNING = "a workspace slot running on a runner in service";

  /** The workspace every row's runner holds — never the service's frozen 1. */
  static final long OWN = 7;

  /** A labelled workspace the service does not own, for the estate's sweep. */
  static final long UNOWNED = 8;

  static final String RUNNER_ID = RunnerProviderStates.RUNNER_ID;

  static final MessageContract CONTRACT =
      new MessageContract(
          CONSUMER,
          MASTERS,
          List.of(
              // --- the greeting ---------------------------------------------------------------
              new Row(
                  event("workspaces-runner:hello"),
                  HOLDING_ITS_WORKSPACE,
                  "ack",
                  ServiceFramesContract::ackAdmitsTheRunnerHoldingItsWorkspace),
              new Row(
                  event("workspaces-runner:hello"),
                  HOLDING_ITS_WORKSPACE,
                  "estate",
                  ServiceFramesContract::estateRemovesOnlyUnownedLabelledWorkspaces),
              new Row(
                  event("workspaces-runner:hello"),
                  QUEUED_FOR_IT,
                  "backlog",
                  ServiceFramesContract::backlogReservesWithAFreeSlot),
              new Row(
                  event("workspaces-runner:hello"),
                  OLDER_VERSION,
                  "upgrade",
                  ServiceFramesContract::upgradeStartsTheSuccessorFromTheImage),
              new Row(
                  event("workspaces-runner:hello"),
                  QUARANTINED_DIALLING_IN,
                  "quarantined",
                  ServiceFramesContract::quarantinedIsKept),
              // --- the claim ------------------------------------------------------------------
              new Row(
                  event("workspaces-runner:reserve"),
                  FREE_SLOT,
                  "take",
                  ServiceFramesContract::takeRunsTheWorkspaceUnderItsVolumeAndLabels),
              new Row(
                  event("workspaces-runner:reserve"),
                  SLOTS_TAKEN,
                  "nothing",
                  ServiceFramesContract::nothingTakesNothingAndParksTheReserve),
              // --- the verbs routed to the owner ----------------------------------------------
              new Row(
                  operation("stopWorkspaceContainer"),
                  SLOT_RUNNING,
                  "stop",
                  ServiceFramesContract::stopStopsTheContainerThenSaysStopped),
              new Row(
                  operation("deleteWorkspaceContainer"),
                  SLOT_RUNNING,
                  "delete",
                  ServiceFramesContract::deleteRemovesTheContainerAndItsVolumeThenSaysDeleted),
              // --- health, login and the runner's own life ------------------------------------
              new Row(
                  operation("requestRunnerLoginCheck"),
                  IN_SERVICE,
                  "probeLogin",
                  ServiceFramesContract::probeLoginProbesAndSaysTheLoginState),
              new Row(
                  operation("requestRunnerHealthcheck"),
                  IN_SERVICE,
                  "healthCheck",
                  ServiceFramesContract::healthCheckIsAnsweredForItsRequest),
              new Row(
                  operation("deleteRunner"),
                  IN_SERVICE,
                  "retire",
                  ServiceFramesContract::aDeletedRetireDecommissionsTheRunner),
              new Row(
                  operation("greenlightRunner"),
                  QUARANTINED_ON_A_SOCKET,
                  "reinstated",
                  ServiceFramesContract::reinstatedLiftsTheQuarantine)));

  private ServiceFramesContract() {}

  private static Trigger event(String event) {
    return Trigger.sent(SERVICE, "event", event);
  }

  private static Trigger operation(String operationId) {
    return Trigger.sent(SERVICE, "operation", operationId);
  }

  // --- the greeting ------------------------------------------------------------------------------

  /**
   * {@code ack} adopting the workspace whose container the node runs: the runner is admitted — the
   * agent home made under its label, the {@code inventory} listing the container and its volume —
   * and keeps the workspace, holding its slot, never stopping or removing it.
   */
  static void ackAdmitsTheRunnerHoldingItsWorkspace(PactMessage message, Path dir)
      throws Exception {
    try (Node node = new Node(dir)) {
      node.seen(OWN, "running", 0, "h7");
      node.rowVolume(OWN);
      String ack = message.text(Map.of("workspaceId", OWN));
      node.host.script =
          (h, frame) -> {
            if (frame instanceof Hello) {
              h.sendRaw(ack);
            }
          };
      node.connect();

      Inventory inventory = node.host.await(Inventory.class);
      assertEquals(List.of(new HeldContainer(OWN, true, "h7")), inventory.containers());
      assertEquals(List.of(OWN), inventory.volumes());
      assertEquals(node.volumes.dotClaude(), inventory.dotClaudeVolume());
      assertEquals(
          Map.of(RunnerProviderStates.IDENTITY.ownerLabel(), RUNNER_ID),
          node.docker.volumes().get(node.volumes.dotClaude()),
          "the agent home, made under the runner's label at its ack");
      node.holding(OWN);
      assertFalse(touched(node, "stop", ws(OWN)) || touched(node, "rm", ws(OWN)), calls(node));
    }
  }

  /**
   * {@code estate} owning the runner's workspace: the labelled container of a row it does not own
   * goes at once, the owned one and an unlabelled look-alike stay; the {@code inventory} says what
   * is left, the workspace image is pulled, and the login is probed ({@code loginState}).
   */
  static void estateRemovesOnlyUnownedLabelledWorkspaces(PactMessage message, Path dir)
      throws Exception {
    try (Node node = new Node(dir)) {
      node.seen(OWN, "running", 0, "h7");
      node.rowVolume(OWN);
      node.seen(UNOWNED, "exited", 0, "h8");
      String lookalike = ws(UNOWNED) + "-copy";
      node.docker.container(lookalike, Map.of());
      loginsPresent(node);
      String image = message.example().getString("workspaceImage");
      // Not on the node yet: the estate pulls it, and the pull takes a second, in which it lands.
      node.docker.answerFor("image-inspect", image, 1, "", "Error: No such image");
      node.docker.hang("pull", 1);
      greetWith(node, 1);
      node.connect();
      node.host.await(Inventory.class);
      int from = node.tap.mark();

      node.host.sendRaw(message.text(Map.of("workspaceId", OWN)));

      node.awaitTrue(() -> !node.docker.containers().containsKey(ws(UNOWNED)));
      assertTrue(node.docker.containers().containsKey(ws(OWN)), "the owned workspace stays");
      assertTrue(node.docker.containers().containsKey(lookalike), "nothing unlabelled is touched");
      Inventory after = decode(node.tap.await("inventory", from, 1), Inventory.class);
      assertEquals(List.of(new HeldContainer(OWN, true, "h7")), after.containers());
      node.awaitTrue(
          () -> node.docker.calls("pull").stream().anyMatch(c -> c.equals(List.of("pull", image))));
      node.docker.answerFor("image-inspect", image, 0, "sha256:ws\n", "");
      LoginState login = decode(node.tap.await("loginState", from, 1), LoginState.class);
      assertEquals(LoginPresence.PRESENT, login.claude());
    }
  }

  /** {@code backlog} with a free slot: the runner, idle until then, sends {@code reserve}. */
  static void backlogReservesWithAFreeSlot(PactMessage message, Path dir) throws Exception {
    try (Node node = new Node(dir)) {
      greetWith(node, 1);
      node.connect();
      node.host.await(Inventory.class);
      Thread.sleep(300);
      assertTrue(node.host.all(Reserve.class).isEmpty(), "nothing queued, nothing reserved");
      int from = node.tap.mark();

      node.host.sendRaw(message.text());

      node.tap.await("reserve", from, 1);
    }
  }

  /**
   * {@code upgrade} to a version with no digest: the image is pulled and the successor started from
   * it at once — the runner's own container's parameters, the version in its name — and no
   * workspace is touched.
   */
  static void upgradeStartsTheSuccessorFromTheImage(PactMessage message, Path dir)
      throws Exception {
    try (Node node = new Node(dir)) {
      JsonObject upgrade = message.example();
      String version = upgrade.getString("version");
      String image = upgrade.getString("image");
      node.seen(OWN, "running", 0, "h7");
      RunnerSelf.inspectable(node.docker);
      String sent = message.text();
      node.host.script =
          (h, frame) -> {
            if (frame instanceof Hello) {
              h.sendRaw(sent);
              h.send(new Ack(RunnerProviderStates.IDENTITY.capabilityVersion(), 0));
            }
          };
      node.connect();

      String successor = RunnerProviderStates.IDENTITY.containerName(RUNNER_ID, version);
      node.awaitTrue(
          () ->
              node.docker.calls("run").stream()
                  .anyMatch(c -> c.contains(successor) && image.equals(c.getLast())));
      assertTrue(
          node.docker.calls("pull").stream().anyMatch(c -> c.getLast().equals(image)), calls(node));
      for (String verb : List.of("stop", "rm")) {
        assertFalse(touched(node, verb, ws(OWN)), calls(node));
      }
    }
  }

  /** {@code quarantined}: the runner keeps the quarantine as its own status. */
  static void quarantinedIsKept(PactMessage message, Path dir) throws Exception {
    try (Node node = new Node(dir)) {
      String sent = message.text();
      node.host.script =
          (h, frame) -> {
            if (frame instanceof Hello) {
              h.send(new Ack(RunnerProviderStates.IDENTITY.capabilityVersion(), 0));
              h.sendRaw(sent);
            }
          };
      node.connect();

      node.awaitTrue(() -> node.session() != null && node.session().quarantined());
    }
  }

  // --- the claim ---------------------------------------------------------------------------------

  /**
   * {@code take} answering the runner's {@code reserve}: {@code docker run} of the row's container
   * with its {@code /workspace} volume, the runner's own labels, every label and variable of the
   * spec and its image; then {@code launched}, and the row holds the slot.
   */
  static void takeRunsTheWorkspaceUnderItsVolumeAndLabels(PactMessage message, Path dir)
      throws Exception {
    try (Node node = new Node(dir)) {
      String take = message.text(Map.of("workspaceId", OWN));
      node.host.script =
          (h, frame) -> {
            if (frame instanceof Hello) {
              h.send(new Ack(RunnerProviderStates.IDENTITY.capabilityVersion(), 1));
              h.send(new Backlog(1));
            } else if (frame instanceof Reserve && h.all(Reserve.class).size() == 1) {
              h.sendRaw(take);
            }
          };
      node.connect();

      assertEquals(OWN, node.host.await(Launched.class).rowId());
      JsonObject spec = message.example().getJsonObject("spec");
      List<String> run =
          node.docker.calls("run").stream()
              .filter(c -> c.contains(ws(OWN)))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("no docker run of " + ws(OWN) + ": " + calls(node)));
      assertPair(run, "--name", ws(OWN));
      assertPair(run, "-v", ws(OWN) + ":/workspace");
      assertPair(run, "--label", RunnerProviderStates.IDENTITY.ownerLabel() + "=" + RUNNER_ID);
      assertPair(run, "--label", RunnerProviderStates.IDENTITY.workLabel() + "=" + OWN);
      spec.getJsonObject("labels")
          .forEach(l -> assertPair(run, "--label", l.getKey() + "=" + l.getValue()));
      spec.getJsonObject("env")
          .forEach(e -> assertPair(run, "-e", e.getKey() + "=" + e.getValue()));
      assertPair(run, "--memory", spec.getString("memoryLimit"));
      assertEquals(spec.getString("image"), run.getLast(), "the spec's image");
      assertEquals(
          Map.of(
              RunnerProviderStates.IDENTITY.ownerLabel(),
              RUNNER_ID,
              RunnerProviderStates.IDENTITY.workLabel(),
              Long.toString(OWN)),
          node.docker.volumes().get(ws(OWN)),
          "the row's volume, keyed on the row id");
      node.holding(OWN);
    }
  }

  /**
   * {@code nothing} answering the runner's {@code reserve}: no workspace is run and the runner asks
   * no more — until a new {@code backlog} says there is work again.
   */
  static void nothingTakesNothingAndParksTheReserve(PactMessage message, Path dir)
      throws Exception {
    try (Node node = new Node(dir)) {
      String nothing = message.text();
      node.host.script =
          (h, frame) -> {
            if (frame instanceof Hello) {
              h.send(new Ack(RunnerProviderStates.IDENTITY.capabilityVersion(), 1));
              h.send(new Backlog(1));
            } else if (frame instanceof Reserve && h.all(Reserve.class).size() == 1) {
              h.sendRaw(nothing);
            }
          };
      node.connect();
      node.host.await(Reserve.class);

      Thread.sleep(800);
      assertEquals(1, node.host.all(Reserve.class).size(), "parked: no second reserve");
      assertTrue(node.docker.calls("run").stream().noneMatch(c -> c.contains(ws(1))), calls(node));
      assertEquals(List.of(), node.session().held());
      node.host.send(new Backlog(1));
      node.host.await(Reserve.class, 2);
    }
  }

  // --- the verbs routed to the owner -------------------------------------------------------------

  /**
   * {@code stop} of the running workspace: {@code docker stop}, then {@code stopped}, slot freed.
   */
  static void stopStopsTheContainerThenSaysStopped(PactMessage message, Path dir) throws Exception {
    try (Node node = new Node(dir)) {
      node.seen(OWN, "running", 0, "h7");
      greetWith(node, 1);
      node.connect().holding(OWN);
      node.host.await(Inventory.class);

      node.host.sendRaw(message.text(Map.of("workspaceId", OWN)));

      assertEquals(new Stopped(OWN), node.host.await(Stopped.class));
      assertTrue(touched(node, "stop", ws(OWN)), calls(node));
      node.holding();
    }
  }

  /**
   * {@code delete} of the running workspace: stopped, then its container and its {@code qits-ws}
   * volume — both named for the row id — removed; then {@code deleted}. The node's agent home is no
   * row's and stays.
   */
  static void deleteRemovesTheContainerAndItsVolumeThenSaysDeleted(PactMessage message, Path dir)
      throws Exception {
    try (Node node = new Node(dir)) {
      node.seen(OWN, "running", 0, "h7");
      node.rowVolume(OWN);
      greetWith(node, 1);
      node.connect().holding(OWN);
      node.host.await(Inventory.class);

      node.host.sendRaw(message.text(Map.of("workspaceId", OWN)));

      assertEquals(new Deleted(OWN), node.host.await(Deleted.class));
      assertFalse(node.docker.containers().containsKey(ws(OWN)));
      assertFalse(node.docker.volumes().containsKey(ws(OWN)));
      List<List<String>> calls = node.docker.calls();
      int stop = indexOf(calls, "stop", ws(OWN));
      int rm = indexOf(calls, "rm", ws(OWN));
      assertTrue(stop >= 0 && stop < rm, "stopped before it is removed: " + calls);
      assertTrue(node.docker.volumes().containsKey(node.volumes.dotClaude()));
      node.holding();
    }
  }

  // --- health, login and the runner's own life ---------------------------------------------------

  /**
   * {@code probeLogin}: the logins are probed again, now, and said in a {@code loginState} of its
   * own — beyond the one the estate made.
   */
  static void probeLoginProbesAndSaysTheLoginState(PactMessage message, Path dir) throws Exception {
    try (Node node = new Node(dir)) {
      loginsPresent(node);
      node.host.script =
          (h, frame) -> {
            if (frame instanceof Hello) {
              h.send(new Ack(RunnerProviderStates.IDENTITY.capabilityVersion(), 1));
              h.send(new Estate(List.of(), RunnerProviderStates.WORKSPACE_IMAGE));
            }
          };
      node.connect();
      node.host.await(LoginState.class);
      long probes = statusProbes(node);
      int from = node.tap.mark();

      node.host.sendRaw(message.text());

      LoginState login = decode(node.tap.await("loginState", from, 1), LoginState.class);
      assertEquals(LoginPresence.PRESENT, login.claude());
      assertEquals(LoginPresence.PRESENT, login.kimi());
      assertTrue(statusProbes(node) > probes, "probed again: " + calls(node));
    }
  }

  /** {@code healthCheck}: answered by one {@code healthChecked} echoing its request id. */
  static void healthCheckIsAnsweredForItsRequest(PactMessage message, Path dir) throws Exception {
    try (Node node = new Node(dir)) {
      greetWith(node, 1);
      node.connect();
      node.host.await(Inventory.class);
      String requestId = UUID.randomUUID().toString();
      int from = node.tap.mark();

      node.host.sendRaw(message.text(Map.of(), frame -> frame.put("requestId", requestId)));

      HealthChecked answer = decode(node.tap.await("healthChecked", from, 1), HealthChecked.class);
      assertEquals(requestId, answer.requestId());
      assertFalse(answer.checks().isEmpty(), "every check reported");
    }
  }

  /**
   * {@code retire} of kind {@code DELETED}: the runner decommissions itself — its held workspace
   * stopped, its revoked client forgotten, the decommission helper started — and exits {@code 0}
   * without dialling again.
   */
  static void aDeletedRetireDecommissionsTheRunner(PactMessage message, Path dir) throws Exception {
    try (Node node = new Node(dir)) {
      node.seen(OWN, "running", 0, "h7");
      RunnerSelf.withStateVolume(node.docker, node.stateDir());
      greetWith(node, 1);
      node.connect().holding(OWN);
      node.host.await(Inventory.class);

      node.host.sendRaw(message.text());

      assertEquals(RunnerExit.OK, node.exit().get(15, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(touched(node, "stop", ws(OWN)), calls(node));
      String helper = RunnerProviderStates.IDENTITY.helperName(RUNNER_ID);
      assertTrue(node.docker.calls("run").stream().anyMatch(c -> c.contains(helper)), calls(node));
      assertFalse(
          java.nio.file.Files.exists(node.stateDir().resolve("client.json")),
          "the revoked client is forgotten");
      assertEquals(1, node.host.upgrades.get(), "no redial");
    }
  }

  /** {@code reinstated} after a {@code quarantined}: the runner's quarantine is lifted. */
  static void reinstatedLiftsTheQuarantine(PactMessage message, Path dir) throws Exception {
    try (Node node = new Node(dir)) {
      node.host.script =
          (h, frame) -> {
            if (frame instanceof Hello) {
              h.send(new Ack(RunnerProviderStates.IDENTITY.capabilityVersion(), 0));
              h.send(new Quarantined("awaiting first health check", "2026-10-05T04:38:19Z"));
            }
          };
      node.connect();
      node.awaitTrue(() -> node.session() != null && node.session().quarantined());

      node.host.sendRaw(message.text());

      node.awaitTrue(() -> !node.session().quarantined());
    }
  }

  // --- helpers -----------------------------------------------------------------------------------

  /** Ack every hello with {@code slots}, as the service greets a runner in service. */
  private static void greetWith(Node node, int slots) {
    node.host.script =
        (h, frame) -> {
          if (frame instanceof Hello) {
            h.send(new Ack(RunnerProviderStates.IDENTITY.capabilityVersion(), slots));
          }
        };
  }

  /** Both logins present on the node, as the probe's two {@code docker run}s answer. */
  private static void loginsPresent(Node node) throws Exception {
    node.docker.answerFor("run", "status", 0, "{\"loggedIn\": true}\n", "");
    node.docker.answerFor("run", LoginProbe.KIMI_CHECK, 0, "", "");
  }

  private static long statusProbes(Node node) {
    return node.docker.calls("run").stream().filter(c -> c.getLast().equals("status")).count();
  }

  private static String ws(long rowId) {
    return Node.names(rowId);
  }

  private static <T> T decode(String text, Class<T> type) {
    return type.cast(RunnerProviderStates.CODEC.decode(new JsonObject(text).getMap()));
  }

  private static void assertPair(List<String> argv, String flag, String value) {
    for (int i = 0; i + 1 < argv.size(); i++) {
      if (argv.get(i).equals(flag) && argv.get(i + 1).equals(value)) {
        return;
      }
    }
    throw new AssertionError("no " + flag + " " + value + " in " + argv);
  }

  private static boolean touched(Node node, String verb, String name) {
    return indexOf(node.docker.calls(), verb, name) >= 0;
  }

  private static int indexOf(List<List<String>> calls, String verb, String name) {
    for (int i = 0; i < calls.size(); i++) {
      List<String> call = FakeDocker.withoutConfig(calls.get(i));
      if (!call.isEmpty() && call.get(0).equals(verb) && call.contains(name)) {
        return i;
      }
    }
    return -1;
  }

  private static String calls(Node node) {
    return node.docker.calls().toString();
  }

  /**
   * The runner's own container as {@code docker inspect} answers it — what a successor copies its
   * parameters from — and its image's config, for {@link #upgradeStartsTheSuccessorFromTheImage}.
   * {@code WorkspacesRunnerTest}'s fixture of the same purpose.
   */
  private static final class RunnerSelf {

    /**
     * The runner's own container with its state volume mounted at {@code stateDir}: what the
     * decommission reads to know which volume to hand its helper.
     */
    static void withStateVolume(FakeDocker docker, Path stateDir) throws Exception {
      String self = "0123456789ab";
      docker.answerFor(
          "inspect",
          self,
          0,
          new JsonArray()
                  .add(
                      new JsonObject()
                          .put("Id", self + "0".repeat(52))
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
                                          .put("Name", "qits-workspaces-runner-state-" + RUNNER_ID)
                                          .put("Destination", stateDir.toString())))
                          .put(
                              "HostConfig",
                              new JsonObject()
                                  .put("RestartPolicy", Map.of("Name", "unless-stopped"))))
                  .encode()
              + "\n",
          "");
    }

    static void inspectable(FakeDocker docker) throws Exception {
      String self = "0123456789ab";
      List<String> env = new ArrayList<>();
      env.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
      env.add("QITS_WORKSPACES_RUNNER_URL=https://workspaces.qits.example.eu");
      env.add("QITS_WORKSPACES_RUNNER_ID=" + RUNNER_ID);
      env.add("QITS_WORKSPACES_RUNNER_SLOTS=2");
      docker.answerFor(
          "inspect",
          self,
          0,
          new JsonArray()
                  .add(
                      new JsonObject()
                          .put("Id", self + "0".repeat(52))
                          .put("Image", "sha256:old")
                          .put(
                              "Config",
                              new JsonObject()
                                  .put("Env", env)
                                  .put(
                                      "Labels",
                                      Map.of(
                                          "qits.workspaces.runner.process",
                                          RUNNER_ID,
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
    }
  }
}
