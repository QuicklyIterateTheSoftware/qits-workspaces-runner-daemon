package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.toolkit.HostFacts;
import eu.wohlben.qits.runner.toolkit.HousekeepingPass;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerDomain;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import eu.wohlben.qits.workspacesrunner.protocol.Delete;
import eu.wohlben.qits.workspacesrunner.protocol.Deleted;
import eu.wohlben.qits.workspacesrunner.protocol.Estate;
import eu.wohlben.qits.workspacesrunner.protocol.HealthCheck;
import eu.wohlben.qits.workspacesrunner.protocol.LaunchFailed;
import eu.wohlben.qits.workspacesrunner.protocol.LoginPresence;
import eu.wohlben.qits.workspacesrunner.protocol.LoginState;
import eu.wohlben.qits.workspacesrunner.protocol.ProbeLogin;
import eu.wohlben.qits.workspacesrunner.protocol.Stop;
import eu.wohlben.qits.workspacesrunner.protocol.Stopped;
import eu.wohlben.qits.workspacesrunner.protocol.Take;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerCodec;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerMessage;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

/**
 * <b>What the workspaces runner is for</b>, as qits-runner-toolkit's {@link RunnerDomain}: the
 * toolkit owns registration, the bearer, the socket, the slot ledger, self-update, decommission,
 * the heartbeat and housekeeping; this class owns the workspace frames and the workspace containers.
 *
 * <p><b>Rollover never drains.</b> {@link #quiescent()} is true whenever no {@code take}, {@code
 * stop} or {@code delete} is half done — never "holds nothing": a workspace container is node state
 * that outlives the runner process, so a runner that waited for its workspaces to end would never
 * hand over. The toolkit starts the successor at once; the successor adopts the running containers
 * through its {@code Hello}'s {@code heldWorkspaces} and its {@code inventory}, and the predecessor
 * still serves {@code stop}/{@code delete} until its {@code retire}.
 *
 * <p><b>What this version does, and what it answers for the rest.</b> qits-842 is the runner's
 * lifecycle: it registers, connects, rolls over, decommissions, answers {@code healthCheck} ({@link
 * NodeHealthCheck}) and collects old workspace images ({@link WorkspaceImagesPass}). The workspace
 * handlers are qits-854's (a {@code WorkspaceLauncher} for {@code take}'s three rungs, {@code stop},
 * {@code delete}, the {@code exited} watcher, {@code inventory} and the {@code estate} reconcile) and
 * the node volumes and login probe are qits-858's ({@code NodeVolumes}, {@code LoginProbe}). Until
 * they land, every frame still gets a safe and honest answer, so a server that sends one is never
 * left guessing and nothing on the node is touched:
 *
 * <ul>
 *   <li>{@code take} → {@code launchFailed{rowId, …}} without ever holding a slot: the server
 *       fails the row with that detail, and nothing is started;
 *   <li>{@code stop}/{@code delete} → {@code stopped}/{@code deleted} when the row has no container
 *       on this node (which, from this version, it cannot have); a container that IS there is left
 *       alone and the frame unanswered, so the server times out with the row unchanged rather than
 *       hearing an answer that is not true;
 *   <li>{@code probeLogin} → {@code loginState{UNKNOWN, UNKNOWN}}: the probe cannot run here yet;
 *   <li>{@code estate} → remembered (its {@code workspaceImage} is what the health check runs and
 *       what housekeeping keeps); nothing is reconciled against {@code owned} — that is qits-854's,
 *       and removing a workspace container on a version that cannot launch one would only ever be
 *       a loss.
 * </ul>
 *
 * <p>Built by {@code RunnerLauncher} from the parsed settings; the class is plain, and {@code
 * WorkspacesRunnerTest} drives it through the toolkit's {@code FakeRunnerHost} and {@code
 * FakeDocker}.
 */
public final class WorkspacesRunnerDomain implements RunnerDomain<WorkspacesRunnerMessage> {

  private static final Logger LOG = Logger.getLogger(WorkspacesRunnerDomain.class);

  /** What a {@code launchFailed} says while this version cannot launch a workspace. */
  static final String TAKE_UNSUPPORTED =
      "this runner version does not launch workspaces yet (qits-854)";

  private static final WorkspacesRunnerCodec CODEC = new WorkspacesRunnerCodec();

  private final RunnerIdentity identity = WorkspacesRunnerIdentity.IDENTITY;
  private final RunnerDocker docker;
  private final WorkspaceNames names;
  private final NodeHealthCheck healthCheck;
  private final Clock clock;

  /** Takes, stops and deletes under way: the rollover waits for zero, and only for that. */
  private final AtomicInteger inFlight = new AtomicInteger();

  /** The latest {@code estate}, kept across reconnects; null before the first. */
  private volatile Estate estate;

  /** Set once this runner learns it was deleted: from then on it starts nothing. */
  private volatile boolean decommissioning;

  /** Built by {@code RunnerLauncher} from the parsed settings, as every embedder's domain is. */
  public WorkspacesRunnerDomain(RunnerSettings settings) {
    this(settings, NodeHealthCheck.DEFAULT_WINDOW, NodeHealthCheck.DEFAULT_POLL, Clock.systemUTC());
  }

  /** The same domain with the health check's clocks a suite shrinks. */
  WorkspacesRunnerDomain(
      RunnerSettings settings, Duration healthWindow, Duration healthPoll, Clock clock) {
    this.docker = RunnerDocker.of(identity, settings);
    this.names = new WorkspaceNames(identity, settings.runnerId());
    this.healthCheck = new NodeHealthCheck(docker, names, healthWindow, healthPoll);
    this.clock = clock;
  }

  @Override
  public DomainCodec<WorkspacesRunnerMessage> codec() {
    return CODEC;
  }

  @Override
  public RunnerCapabilities capabilities(HostFacts host) {
    return new RunnerCapabilities(host.docker(), host.arch(), host.os(), Map.of());
  }

  /**
   * Nothing is swept at a session start. The keep set of a workspace runner is the server's
   * {@code owned} list, which arrives in the {@code estate} after the {@code Ack} — never the
   * carried items, and never an in-memory guess; qits-854 reconciles there.
   */
  @Override
  public void onSessionStart(RunnerSession session, List<String> carried) {}

  @Override
  public void onAck(Ack ack, RunnerSession session) {}

  /**
   * Nothing is removed for a carried workspace the host did not adopt: its container and its
   * volume are a person's work, and whether the row is still this runner's is the next {@code
   * estate}'s to say.
   */
  @Override
  public void onDropped(List<String> workIds, RunnerSession session) {}

  @Override
  public void onMessage(WorkspacesRunnerMessage message, RunnerSession session) {
    switch (message) {
      case Estate e -> onEstate(e);
      case Take take -> onTake(take, session);
      case Stop stop -> onStop(stop, session);
      case Delete delete -> onDelete(delete, session);
      case ProbeLogin _ -> onProbeLogin(session);
      case HealthCheck _ -> onHealthCheck(session);
      default ->
          // inventory, launched, launchFailed, stopped, deleted, exited, loginState and
          // healthChecked are runner → server; a host echoing one is not a conversation this has.
          LOG.debugf(
              "workspaces-runner ignored a %s from its host", message.getClass().getSimpleName());
    }
  }

  @Override
  public boolean quiescent() {
    return inFlight.get() == 0;
  }

  /**
   * Stop every held workspace's container, so nothing of this runner runs on past its deletion;
   * the toolkit's decommission helper then removes every container and volume labelled {@code
   * qits.workspaces.runner=<id>}, the state volume and the runner's own container. No {@code
   * teardown()} item is needed: everything the runner creates carries that label.
   */
  @Override
  public void onDecommission(RunnerSession session) {
    decommissioning = true;
    Duration deadline = docker.deadline();
    for (String workId : session.held()) {
      Optional<Long> rowId = rowId(workId);
      if (rowId.isEmpty()) {
        continue;
      }
      String name = names.container(rowId.get());
      ContainersDriver.OpResult stopped = session.driver().stop(name, deadline);
      if (!stopped.ok()) {
        LOG.warnf("workspaces-runner could not stop %s: %s", name, stopped.detail());
      }
    }
  }

  /** The workspace images this node no longer needs, after the toolkit's own passes. */
  @Override
  public List<HousekeepingPass> housekeeping() {
    return List.of(new WorkspaceImagesPass(docker, this::workspaceImage));
  }

  /** The latest {@code estate}'s workspace image, or null before one. */
  String workspaceImage() {
    Estate e = estate;
    return e == null ? null : e.workspaceImage();
  }

  // --- the frames ---------------------------------------------------------------------------------

  private void onEstate(Estate e) {
    estate = e;
    LOG.infof(
        "workspaces-runner's estate: %d owned workspace(s), workspace image %s",
        e.owned().size(), e.workspaceImage());
  }

  /**
   * Never a slot: a take this version cannot launch is answered at once, so the server frees the
   * row (FAILED with the detail) instead of waiting on a container that is not coming. qits-854's
   * {@code WorkspaceLauncher} replaces this.
   *
   * <p>The {@code taken}/{@code released} pair is the slot ledger's bookkeeping, not a held slot:
   * a {@code take} is the answer to the one outstanding {@code Reserve}, and only {@code taken}
   * tells the ledger it was answered. Without it the runner would never reserve again on this
   * socket; with it, the next queued row is asked for — and failed as plainly — at once.
   */
  private void onTake(Take take, RunnerSession session) {
    inFlight.incrementAndGet();
    try {
      LOG.warnf(
          "workspaces-runner cannot take workspace %d: %s", take.rowId(), TAKE_UNSUPPORTED);
      session.send(new LaunchFailed(take.rowId(), TAKE_UNSUPPORTED));
      String workId = Long.toString(take.rowId());
      session.taken(workId);
      session.released(workId);
    } finally {
      inFlight.decrementAndGet();
    }
  }

  private void onStop(Stop stop, RunnerSession session) {
    inFlight.incrementAndGet();
    try {
      if (absent(stop.rowId(), session)) {
        session.send(new Stopped(stop.rowId()));
      }
    } finally {
      inFlight.decrementAndGet();
    }
  }

  private void onDelete(Delete delete, RunnerSession session) {
    inFlight.incrementAndGet();
    try {
      if (absent(delete.rowId(), session)) {
        session.send(new Deleted(delete.rowId()));
      }
    } finally {
      inFlight.decrementAndGet();
    }
  }

  /**
   * Whether the row has no container on this node — the one case this version can answer {@code
   * stop} and {@code delete} truthfully for (qits-854 does the rest). A docker that does not answer
   * is not an absence: the frame then goes unanswered, and the server times out with the row
   * unchanged.
   */
  private boolean absent(long rowId, RunnerSession session) {
    String name = names.container(rowId);
    try {
      if (session.driver().inspect(name, docker.deadline()).isEmpty()) {
        return true;
      }
      LOG.warnf(
          "workspaces-runner leaves %s alone: this version does not stop or delete workspaces yet"
              + " (qits-854)",
          name);
    } catch (RuntimeException e) {
      LOG.warnf("workspaces-runner could not look for %s: %s", name, e.getMessage());
    }
    return false;
  }

  /** {@code UNKNOWN} is the truth until qits-858's {@code LoginProbe}: the probe cannot run here. */
  private void onProbeLogin(RunnerSession session) {
    session.send(
        new LoginState(LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, clock.instant().toString()));
  }

  private void onHealthCheck(RunnerSession session) {
    if (decommissioning) {
      return;
    }
    session.send(healthCheck.run(session, workspaceImage()));
  }

  /** A held work id back to the row id it was taken as; anything else is not a workspace. */
  private static Optional<Long> rowId(String workId) {
    try {
      return Optional.of(Long.parseLong(workId));
    } catch (NumberFormatException notARow) {
      return Optional.empty();
    }
  }
}
