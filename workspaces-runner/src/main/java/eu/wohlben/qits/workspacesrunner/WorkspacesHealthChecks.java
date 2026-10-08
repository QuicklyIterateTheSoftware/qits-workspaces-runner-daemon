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
 *       runner's login when it is not; {@code data: {image, pulled, pulling, id, created}}. Writes:
 *       the pull. It waits for that pull one docker deadline at most, and <b>a pull still under way
 *       then is ok</b> ({@code pulling: true}): a new workspace-image release takes minutes to land
 *       on every node, a launch joins the same pull, and {@link WorkspaceImagesPass} keeps the
 *       previous tags until it is done — the node is not at fault, and waiting it out is what timed
 *       the check out and quarantined it (qits-959). A pull that failed is still a failure.
 *   <li>{@code selfTest} — {@link NodeHealthCheck}: a workspace image started under the runner's
 *       label, seen running, removed; {@code data: {image, pin}}, {@code image} the one actually
 *       started. That is the pin when it is here; while it is not (still being pulled, or its pull
 *       failed) the newest other tag of the pin's repository on the node — the image a launch would
 *       otherwise have proven — rather than blocking on the pull. A node with no workspace image at
 *       all yet (its first pull) has nothing to prove and fails. Writes: that one container.
 *   <li>{@code nodeInventory} — {@link NodeInventoryCheck}. Read-only.
 *   <li>{@code login} — the last login probe's result, with no new probe; {@code data: {claude,
 *       kimi, checkedAt}}. Read-only, and always ok: presence is reported by {@code loginState} and
 *       is the operator's to change, so an absent login must never hold a runner in quarantine.
 *   <li>{@code session} — {@code data: {connected, connectedSince, slots, held}}, the slots of the
 *       last {@code Ack} and the rows the toolkit's slot ledger holds. Read-only.
 *   <li>{@code workspaceMemory} — {@link WorkspaceMemoryCheck}: each running workspace's cgroup
 *       memory cap, current use and {@code oom}/{@code oom_kill} counters, plus the node's {@code
 *       MemTotal}/{@code SwapTotal} and whether the running workspaces' own caps together outgrow
 *       it. Writes nothing; <b>always ok</b>, exactly like {@code login} — a workspace OOM-killed
 *       at its own cap is that workspace's affair, never a reason to quarantine the node.
 * </ol>
 */
final class WorkspacesHealthChecks {

  /** What the domain knows of its session, as the {@code session} check reads it. */
  record SessionFacts(boolean connected, Instant connectedSince, Integer slots) {}

  private WorkspacesHealthChecks() {}

  static void registerDefaults(
      HealthChecks checks,
      RunnerDocker docker,
      NodeImages images,
      NodeHealthCheck selfTest,
      Duration selfTestWindow,
      NodeInventoryCheck inventory,
      Supplier<String> workspaceImage,
      Supplier<LoginState> lastLogin,
      Supplier<SessionFacts> session,
      RunnerHealthCheck workspaceMemory) {
    Duration call = docker.deadline();
    checks
        .register(named("docker", null, s -> docker(docker)))
        .register(
            named(
                "workspaceImage",
                call.multipliedBy(3),
                s -> image(docker, images, s, workspaceImage)))
        .register(
            named(
                "selfTest",
                selfTestWindow.plus(call.multipliedBy(6)),
                s -> selfTest(docker, images, selfTest, s, workspaceImage.get())))
        .register(inventory)
        .register(named("login", null, s -> login(lastLogin.get())))
        .register(named("session", null, s -> session(session.get(), s)))
        .register(workspaceMemory);
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
      RunnerDocker docker,
      NodeImages images,
      RunnerSession session,
      Supplier<String> workspaceImage) {
    String image = workspaceImage.get();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("image", image);
    if (image == null || image.isBlank()) {
      return RunnerHealthCheck.Result.failed(
          "the runner has had no estate yet, so it does not know the workspace image", data);
    }
    boolean here = NodeImages.present(docker, image);
    if (!here) {
      // The same pull a launch or the estate's pre-pull may already have under way, joined — and
      // waited for one docker call at most: past that it goes on, for them, without this check.
      switch (images.ensure(session, image, docker.deadline())) {
        case NodeImages.Here _ -> {}
        case NodeImages.Pulling _ -> {
          data.put("pulled", false);
          data.put("pulling", true);
          data.put("id", null);
          data.put("created", null);
          return RunnerHealthCheck.Result.ok(
              image + " is still being pulled; not a node fault", data);
        }
        case NodeImages.Failed failed -> {
          data.put("pulled", true);
          data.put("pulling", false);
          return RunnerHealthCheck.Result.failed(failed.detail(), data);
        }
      }
    }
    data.put("pulled", !here);
    data.put("pulling", false);
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

  /**
   * {@link NodeHealthCheck} against the pin when it is here, else against the newest other tag of
   * its repository the node has — never a wait on the pin's pull, which can outlast this check's
   * deadline and the server's patience many times over (qits-959). Proving an older tag runs proves
   * what the check is for: docker starts a workspace image under the runner's label, and the node
   * keeps one ({@link WorkspaceImagesPass}) until the pin is here.
   */
  private static RunnerHealthCheck.Result selfTest(
      RunnerDocker docker,
      NodeImages images,
      NodeHealthCheck selfTest,
      RunnerSession session,
      String pin) {
    Map<String, Object> data = new LinkedHashMap<>();
    String image = pin;
    if (pin != null && !pin.isBlank() && !NodeImages.present(docker, pin)) {
      Optional<String> fallback = newestOtherTag(docker, pin);
      if (fallback.isEmpty()) {
        data.put("image", null);
        data.put("pin", pin);
        return RunnerHealthCheck.Result.failed(
            pin
                + (images.pulling(pin)
                    ? " is still being pulled"
                    : " is not on this node and is not being pulled")
                + ", and the node has no other "
                + WorkspaceImagesPass.repository(pin)
                + " image to self-test yet",
            data);
      }
      image = fallback.get();
    }
    HealthChecked checked = selfTest.run(session, image);
    data.put("image", image);
    data.put("pin", pin);
    String detail =
        image == null || image.equals(pin)
            ? checked.detail()
            : checked.detail() + " (" + pin + " is not here yet)";
    return new RunnerHealthCheck.Result(checked.ok(), detail, data);
  }

  /**
   * The newest tag of {@code pin}'s repository on the node other than {@code pin}, if any. The
   * toolkit's {@link LifecycleArgv#imageList} answers {@code <repository>:<tag>|<id>} and no
   * creation time, so "newest" is docker's own order: {@code docker image ls} lists newest-created
   * first, and the first match is taken. A {@code <none>} tag is a dangling layer set, never an
   * image to start by name; a listing that fails or was cut short offers nothing.
   */
  static Optional<String> newestOtherTag(RunnerDocker docker, String pin) {
    String repository = WorkspaceImagesPass.repository(pin);
    RunnerDocker.Result listed = docker.run(LifecycleArgv.imageList(docker.binary()));
    if (!listed.ok() || listed.stdoutTruncated()) {
      return Optional.empty();
    }
    for (String line : listed.stdout().split("\\R")) {
      String ref = line.strip().split("\\|", -1)[0].strip();
      int colon = ref.lastIndexOf(':');
      if (colon <= 0
          || !ref.substring(0, colon).equals(repository)
          || ref.endsWith(":<none>")
          || ref.equals(pin)) {
        continue;
      }
      return Optional.of(ref);
    }
    return Optional.empty();
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
