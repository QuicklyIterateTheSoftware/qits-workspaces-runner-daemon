package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import eu.wohlben.qits.workspacesrunner.health.HealthChecks;
import eu.wohlben.qits.workspacesrunner.health.RunnerHealthCheck;
import eu.wohlben.qits.workspacesrunner.protocol.CheckResult;
import eu.wohlben.qits.workspacesrunner.protocol.HealthChecked;
import eu.wohlben.qits.workspacesrunner.protocol.LoginState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The workspaces runner's default health checks, all run on every {@code healthCheck}, in this
 * order; the answer is ok only when every one is:
 *
 * <ol>
 *   <li>{@code docker} — the daemon answers; {@code data: {serverVersion}}. Read-only.
 *   <li>{@code workspaceImage} — the estate's workspace image is on the node, pulled under the
 *       runner's login when it is not; {@code data: {image, pulled, id, created}}. Writes: the pull.
 *   <li>{@code selfTest} — {@link NodeHealthCheck}, unchanged: the image started under the runner's
 *       label, seen running, removed; {@code data: {image}}. Writes: that one container.
 *   <li>{@code nodeInventory} — {@link NodeInventoryCheck}. Read-only.
 *   <li>{@code login} — the last login probe's result, with no new probe; {@code data: {claude,
 *       kimi, checkedAt}}. Read-only, and always ok: presence is reported by {@code loginState} and
 *       is the operator's to change, so an absent login must never hold a runner in quarantine.
 *   <li>{@code session} — {@code data: {connected, connectedSince, slots, held}}, the slots of the
 *       last {@code Ack} and the rows the toolkit's slot ledger holds, then the workspace memory
 *       the {@code Hello} announced, under the same keys and only when set. Read-only.
 * </ol>
 */
final class WorkspacesHealthChecks {

  /** What the domain knows of its session, as the {@code session} check reads it. */
  record SessionFacts(
      boolean connected, Instant connectedSince, Integer slots, WorkspaceLimits limits) {}

  private WorkspacesHealthChecks() {}

  static void registerDefaults(
      HealthChecks checks,
      RunnerDocker docker,
      NodeHealthCheck selfTest,
      Duration selfTestWindow,
      NodeInventoryCheck inventory,
      Supplier<String> workspaceImage,
      Supplier<LoginState> lastLogin,
      Supplier<SessionFacts> session) {
    Duration call = docker.deadline();
    checks
        .register(named("docker", null, s -> docker(docker)))
        .register(named("workspaceImage", call.multipliedBy(3), s -> image(docker, s, workspaceImage)))
        .register(
            named(
                "selfTest",
                selfTestWindow.plus(call.multipliedBy(6)),
                s -> selfTest(selfTest, s, workspaceImage.get())))
        .register(inventory)
        .register(named("login", null, s -> login(lastLogin.get())))
        .register(named("session", null, s -> session(session.get(), s)));
  }

  /** The {@code healthChecked} for a run of {@code checks}: ok only when all of them are. */
  static HealthChecked answer(String requestId, List<HealthChecks.Outcome> outcomes) {
    List<String> failed = new ArrayList<>();
    for (HealthChecks.Outcome o : outcomes) {
      if (!o.ok()) {
        failed.add(o.name() + ": " + o.detail());
      }
    }
    String detail =
        failed.isEmpty()
            ? "all " + outcomes.size() + " checks passed"
            : String.join("; ", failed);
    return new HealthChecked(
        failed.isEmpty(),
        detail,
        requestId,
        outcomes.stream()
            .map(
                o ->
                    new CheckResult(o.name(), o.ok(), o.detail(), o.data()))
            .toList());
  }

  // --- the checks --------------------------------------------------------------------------------

  private static RunnerHealthCheck.Result docker(RunnerDocker docker) {
    RunnerDocker.Result answered =
        docker.run(List.of(docker.binary(), "version", "--format", "{{.Server.Version}}"));
    Map<String, Object> data = new LinkedHashMap<>();
    String version = answered.ok() ? answered.stdout().strip() : "";
    data.put("serverVersion", version.isEmpty() ? null : version);
    if (!answered.ok()) {
      return RunnerHealthCheck.Result.failed("docker did not answer: " + answered.detail(), data);
    }
    if (version.isEmpty()) {
      return RunnerHealthCheck.Result.failed("docker answered no server version", data);
    }
    return RunnerHealthCheck.Result.ok("docker " + version, data);
  }

  private static RunnerHealthCheck.Result image(
      RunnerDocker docker, RunnerSession session, Supplier<String> workspaceImage) {
    String image = workspaceImage.get();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("image", image);
    if (image == null || image.isBlank()) {
      return RunnerHealthCheck.Result.failed(
          "the runner has had no estate yet, so it does not know the workspace image", data);
    }
    boolean here = NodeImages.present(docker, image);
    data.put("pulled", !here);
    if (!here) {
      Optional<String> failure = session.registry().pull(image);
      if (failure.isPresent()) {
        return RunnerHealthCheck.Result.failed(failure.get(), data);
      }
    }
    RunnerDocker.Result inspected =
        docker.run(
            List.of(
                docker.binary(),
                "image",
                "inspect",
                "--format",
                "{{.Id}}|{{.Created}}",
                LifecycleArgv.requireImage(image)));
    String[] f = inspected.ok() ? inspected.stdout().strip().split("\\|", -1) : new String[0];
    data.put("id", f.length > 0 && !f[0].isBlank() ? f[0].strip() : null);
    data.put("created", f.length > 1 && !f[1].isBlank() ? f[1].strip() : null);
    if (!inspected.ok()) {
      return RunnerHealthCheck.Result.failed(
          image + " is not readable on this node: " + inspected.detail(), data);
    }
    return RunnerHealthCheck.Result.ok(image + (here ? " is here" : " was pulled"), data);
  }

  private static RunnerHealthCheck.Result selfTest(
      NodeHealthCheck selfTest, RunnerSession session, String image) {
    HealthChecked checked = selfTest.run(session, image);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("image", image);
    return new RunnerHealthCheck.Result(checked.ok(), checked.detail(), data);
  }

  private static RunnerHealthCheck.Result login(LoginState last) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (last == null) {
      data.put("claude", null);
      data.put("kimi", null);
      data.put("checkedAt", null);
      return RunnerHealthCheck.Result.ok("the login has not been probed yet", data);
    }
    data.put("claude", last.claude().name());
    data.put("kimi", last.kimi().name());
    data.put("checkedAt", last.checkedAt());
    return RunnerHealthCheck.Result.ok(
        "claude " + last.claude() + ", kimi " + last.kimi() + " at " + last.checkedAt(), data);
  }

  private static RunnerHealthCheck.Result session(SessionFacts facts, RunnerSession session) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("connected", facts.connected());
    data.put("connectedSince", facts.connectedSince() == null ? null : facts.connectedSince().toString());
    data.put("slots", facts.slots());
    data.put("held", new ArrayList<>(session.held()));
    data.putAll(facts.limits().announced());
    String detail =
        facts.connected()
            ? "connected since " + facts.connectedSince() + ", " + session.held().size() + " of "
                + facts.slots() + " slot(s) held"
            : "not connected";
    return new RunnerHealthCheck.Result(facts.connected(), detail, data);
  }

  // --- the adapter -------------------------------------------------------------------------------

  /** A check body; {@link RunnerHealthCheck#run} without the name. */
  private interface Body {
    RunnerHealthCheck.Result run(RunnerSession session) throws Exception;
  }

  private static RunnerHealthCheck named(String name, Duration deadline, Body body) {
    return new RunnerHealthCheck() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Duration deadline() {
        return deadline;
      }

      @Override
      public Result run(RunnerSession session) throws Exception {
        return body.run(session);
      }
    };
  }
}
