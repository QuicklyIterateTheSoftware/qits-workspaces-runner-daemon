package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import eu.wohlben.qits.workspacesrunner.protocol.HealthChecked;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The answer to {@code healthCheck}: can this node run a workspace at all? The pinned workspace
 * image is pulled when it is absent (under the runner's own registry login), started detached under
 * the runner's ownership label with no environment, watched until docker reports it {@code
 * running} — within {@link #DEFAULT_WINDOW} — and removed again; the outcome is {@link
 * HealthChecked}. A new runner is quarantined until one passes.
 *
 * <p><b>No environment, no mount, the default bridge.</b> Without an address the workspace's own
 * daemon starts and idles (qits-workspace-daemon {@code ControlSocket}), which is exactly "the image
 * runs here"; anything more would be a workspace, and a workspace is the server's to ask for.
 *
 * <p>The container carries {@code qits.workspaces.runner=<id>} and no work label: it is nobody's
 * workspace, and a leftover (a runner killed mid-check) is removed by the next check before it
 * starts and, at the latest, with everything else of the runner at decommission.
 */
final class NodeHealthCheck {

  private static final Logger LOG = Logger.getLogger(NodeHealthCheck.class);

  /** How long the started image has to be reported {@code running}. */
  static final Duration DEFAULT_WINDOW = Duration.ofSeconds(30);

  /** How often docker is asked within the window. */
  static final Duration DEFAULT_POLL = Duration.ofSeconds(1);

  private final RunnerDocker docker;
  private final WorkspaceNames names;
  private final Duration window;
  private final Duration poll;
  private final NodeImages images;

  NodeHealthCheck(
      RunnerDocker docker,
      WorkspaceNames names,
      Duration window,
      Duration poll,
      NodeImages images) {
    this.docker = docker;
    this.names = names;
    this.window = window;
    this.poll = poll;
    this.images = images;
  }

  /** Run the check for {@code image} (null before the first {@code estate}) and answer it. */
  HealthChecked run(RunnerSession session, String image) {
    if (image == null || image.isBlank()) {
      return failed("the runner has had no estate yet, so it does not know the workspace image");
    }
    Optional<String> notHere = images.ensure(session, image);
    if (notHere.isPresent()) {
      return failed(notHere.get());
    }
    ContainersDriver driver = session.driver();
    Duration deadline = docker.deadline();
    String name = names.healthCheck();
    // A leftover of a check that was cut short holds the name; nothing of it is worth keeping.
    driver.remove(name, deadline);
    try {
      ContainersDriver.Started started =
          driver.run(
              ContainerSpec.builder(image).pullPolicy(ContainerSpec.PullPolicy.NEVER).build(),
              name,
              Map.of(session.identity().ownerLabel(), session.runnerId()),
              LifecyclePolicy.ephemeral(null),
              deadline);
      if (!started.started()) {
        return failed("docker could not start " + image + ": " + brief(started.detail()));
      }
      String status = awaitRunning(driver, name, deadline);
      if (!"running".equals(status)) {
        return failed(
            image
                + " did not run within "
                + window.toSeconds()
                + "s (last status: "
                + (status.isEmpty() ? "unknown" : status)
                + ")");
      }
      return ok(image + " ran on this node");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return failed("the health check was interrupted");
    } catch (RuntimeException e) {
      return failed("docker did not answer: " + brief(e.getMessage()));
    } finally {
      ContainersDriver.OpResult gone = driver.remove(name, deadline);
      if (!gone.ok()) {
        LOG.warnf("workspaces-runner could not remove its health check %s: %s", name, gone.detail());
      }
    }
  }

  /** The last status docker reported, {@code running} as soon as it is. */
  private String awaitRunning(ContainersDriver driver, String name, Duration deadline)
      throws InterruptedException {
    long until = System.nanoTime() + window.toNanos();
    String status = "";
    while (true) {
      Optional<ContainersDriver.Observed> seen = driver.inspect(name, deadline);
      status = seen.map(ContainersDriver.Observed::status).orElse("");
      if ("running".equals(status) || System.nanoTime() >= until) {
        return status;
      }
      Thread.sleep(poll.toMillis());
    }
  }

  private static HealthChecked ok(String detail) {
    LOG.infof("workspaces-runner health check passed: %s", detail);
    return new HealthChecked(true, detail);
  }

  private static HealthChecked failed(String detail) {
    LOG.warnf("workspaces-runner health check failed: %s", detail);
    return new HealthChecked(false, detail);
  }

  private static String brief(String text) {
    String s = text == null ? "" : text.strip();
    return s.length() > 400 ? s.substring(0, 400) + "…" : s;
  }
}
