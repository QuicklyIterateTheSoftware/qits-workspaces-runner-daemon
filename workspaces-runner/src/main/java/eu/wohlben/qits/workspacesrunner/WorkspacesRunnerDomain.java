package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.toolkit.HostFacts;
import eu.wohlben.qits.runner.toolkit.HousekeepingPass;
import eu.wohlben.qits.runner.toolkit.OwnResources;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerDomain;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import eu.wohlben.qits.workspacesrunner.protocol.Delete;
import eu.wohlben.qits.workspacesrunner.protocol.Deleted;
import eu.wohlben.qits.workspacesrunner.protocol.Estate;
import eu.wohlben.qits.workspacesrunner.protocol.Exited;
import eu.wohlben.qits.workspacesrunner.protocol.HealthCheck;
import eu.wohlben.qits.workspacesrunner.protocol.HeldContainer;
import eu.wohlben.qits.workspacesrunner.protocol.Inventory;
import eu.wohlben.qits.workspacesrunner.protocol.LaunchFailed;
import eu.wohlben.qits.workspacesrunner.protocol.Launched;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
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
 * at its session start and through its {@code inventory}, and the predecessor still serves {@code
 * stop}/{@code delete} until its {@code retire}.
 *
 * <p><b>A slot is a running workspace container</b>, counted by the toolkit's slot ledger and fed
 * from docker, never from a guess: the session start lists the running containers under {@code
 * qits.workspaces.runner=<id>} and reports each {@code taken}; a {@code launched} is {@code taken};
 * {@code stopped}, {@code deleted} and {@code exited} are {@code released}. The ledger sends {@code
 * reserve} and parks on {@code nothing}; nothing here decides when to ask for work.
 *
 * <p><b>The server's {@code estate} is the desired state</b> (the dossier's node-reconcile table):
 * a labelled workspace container whose row is not in {@code owned} is removed at once ({@link
 * OwnResources#reconcile}), a labelled {@code -ws-} volume at the next housekeeping pass ({@link
 * WorkspaceVolumesPass}) — both only after an {@code estate} arrived in this session, so a runner
 * that has not heard the server's list yet removes nothing. The node volumes ({@code dot-claude},
 * {@code m2}, {@code pnpm}) go only with the runner, at decommission. Nothing without the label is
 * ever touched.
 *
 * <p><b>Exits are watched by polling</b>, every {@link Timing#watchEvery}: neither
 * qits-containers-driver nor the toolkit offers {@code docker events}, and every docker call here
 * goes through the toolkit's bounded process seam, which a stream that never ends does not fit. A
 * row that was running and is not any more, with no {@code stop} of ours in flight, is {@code
 * exited{rowId, exitCode}}; whatever changed is a new {@code inventory}.
 *
 * <p>Built by {@code RunnerLauncher} from the parsed settings; the class is plain, and {@code
 * WorkspacesRunnerTest} drives it through the toolkit's {@code FakeRunnerHost} and {@code
 * FakeDocker}.
 */
public final class WorkspacesRunnerDomain implements RunnerDomain<WorkspacesRunnerMessage> {

  private static final Logger LOG = Logger.getLogger(WorkspacesRunnerDomain.class);

  private static final WorkspacesRunnerCodec CODEC = new WorkspacesRunnerCodec();

  /**
   * The node's clocks: the health check's window and poll, how often exits are looked for, and
   * the login probe's cadence — every five minutes while a login is ABSENT, hourly otherwise.
   */
  record Timing(
      Duration healthWindow,
      Duration healthPoll,
      Duration watchEvery,
      Duration loginAbsentEvery,
      Duration loginPresentEvery) {

    static Timing defaults() {
      return new Timing(
          NodeHealthCheck.DEFAULT_WINDOW,
          NodeHealthCheck.DEFAULT_POLL,
          Duration.ofSeconds(5),
          Duration.ofMinutes(5),
          Duration.ofHours(1));
    }
  }

  private final RunnerIdentity identity = WorkspacesRunnerIdentity.IDENTITY;
  private final RunnerDocker docker;
  private final WorkspaceNames names;
  private final NodeVolumes volumes;
  private final WorkspaceContainers containers;
  private final WorkspaceLauncher launcher;
  private final NodeHealthCheck healthCheck;
  private final LoginProbe loginProbe;
  private final Timing timing;
  private final Clock clock;

  /** The exit watch and the login schedule; daemon threads, so they never hold the process. */
  private final ScheduledExecutorService timer;

  /** Takes, stops and deletes under way: the rollover waits for zero, and only for that. */
  private final AtomicInteger inFlight = new AtomicInteger();

  /** One lock per row: a take, a stop and a delete of one row never interleave. */
  private final Map<Long, ReentrantLock> rowLocks = new ConcurrentHashMap<>();

  /** Whether each row's container was running when the runner last looked. */
  private final Map<Long, Boolean> lastRunning = new ConcurrentHashMap<>();

  /**
   * Rows taken that no {@code estate} has listed yet: an {@code estate} the server sent before it
   * claimed the row can be read after the take, and must not remove what the take just made.
   */
  private final Set<Long> unconfirmed = ConcurrentHashMap.newKeySet();

  /** The latest {@code estate}, kept across reconnects for its image; null before the first. */
  private volatile Estate estate;

  /** This session's {@code estate.owned}; null until one arrived in this session. */
  private volatile Set<Long> sessionOwned;

  /** Between this session's {@code Ack} and its loss: when inventories and exits are sent. */
  private volatile boolean live;

  /** Set once this runner learns it was deleted: from then on it starts nothing. */
  private volatile boolean decommissioning;

  /** The handle the toolkit gave: the same across reconnects, null before the first session. */
  private volatile RunnerSession session;

  private final Object inventoryLock = new Object();
  private Inventory lastInventory;

  private ScheduledFuture<?> watch;
  private ScheduledFuture<?> nextProbe;

  /** Built by {@code RunnerLauncher} from the parsed settings, as every embedder's domain is. */
  public WorkspacesRunnerDomain(RunnerSettings settings) {
    this(settings, Timing.defaults(), Clock.systemUTC());
  }

  /** The same domain with the node's clocks a suite shrinks. */
  WorkspacesRunnerDomain(RunnerSettings settings, Timing timing, Clock clock) {
    this.docker = RunnerDocker.of(identity, settings);
    this.names = new WorkspaceNames(identity, settings.runnerId());
    this.volumes = new NodeVolumes(identity, settings.runnerId());
    this.containers = new WorkspaceContainers(identity, settings.runnerId(), docker);
    this.launcher = new WorkspaceLauncher(docker, names, volumes, containers);
    this.healthCheck =
        new NodeHealthCheck(docker, names, timing.healthWindow(), timing.healthPoll());
    this.loginProbe =
        new LoginProbe(
            identity,
            settings.runnerId(),
            new RunnerDocker(identity, settings.dockerBinary(), LoginProbe.DEADLINE),
            volumes,
            clock);
    this.timing = timing;
    this.clock = clock;
    ScheduledThreadPoolExecutor pool =
        new ScheduledThreadPoolExecutor(
            2,
            r -> {
              Thread t = new Thread(r, "workspaces-runner-node");
              t.setDaemon(true);
              return t;
            });
    pool.setRemoveOnCancelPolicy(true);
    this.timer = pool;
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
   * The running workspace containers, read from docker, each reported to the slot ledger as held:
   * a successor that adopts four running workspaces has four slots taken before it asks for more.
   * A listing docker did not answer reports nothing — the exit watch and the next session look
   * again — rather than a guess. Nothing is removed here: the keep set is the server's {@code
   * owned}, which arrives in the {@code estate} after the {@code Ack}.
   */
  @Override
  public void onSessionStart(RunnerSession session, List<String> carried) {
    this.session = session;
    live = false;
    sessionOwned = null;
    holdRunning(session, null);
  }

  /**
   * Admitted: the node's agent home is made (before the operator's login command could make it
   * unlabelled), the {@code inventory} goes out, and the exit watch runs from here on.
   */
  @Override
  public void onAck(Ack ack, RunnerSession session) {
    this.session = session;
    if (decommissioning) {
      return;
    }
    live = true;
    ContainersDriver.OpResult home = volumes.ensureDotClaude(session.driver(), docker.deadline());
    if (!home.ok()) {
      LOG.warnf("workspaces-runner could not create its agent home: %s", home.detail());
    }
    sendInventory(session, true);
    startWatch();
  }

  /**
   * The host did not adopt these carried workspaces, so the ledger let their slots go — but a
   * container that still runs still holds one. They are taken again; whether the rows are still
   * this runner's is the next {@code estate}'s to say, and their containers are a person's work
   * until it does.
   */
  @Override
  public void onDropped(List<String> workIds, RunnerSession session) {
    holdRunning(session, Set.copyOf(workIds));
  }

  @Override
  public void onSessionLost() {
    live = false;
    sessionOwned = null;
  }

  @Override
  public void onMessage(WorkspacesRunnerMessage message, RunnerSession session) {
    this.session = session;
    switch (message) {
      case Estate e -> onEstate(e, session);
      case Take take -> onTake(take, session);
      case Stop stop -> onStop(stop, session);
      case Delete delete -> onDelete(delete, session);
      case ProbeLogin _ -> probeLogin(session);
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
   * qits.workspaces.runner=<id>} — the workspaces' and the node's agent home and caches with them —
   * the state volume and the runner's own container. No {@code teardown()} item is needed:
   * everything the runner creates carries that label.
   */
  @Override
  public void onDecommission(RunnerSession session) {
    decommissioning = true;
    live = false;
    timer.shutdownNow();
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

  /** The workspace images and the unowned workspace volumes, after the toolkit's own passes. */
  @Override
  public List<HousekeepingPass> housekeeping() {
    return List.of(
        new WorkspaceImagesPass(docker, this::workspaceImage),
        new WorkspaceVolumesPass(volumes, this::volumesToKeep));
  }

  /** The latest {@code estate}'s workspace image, or null before one. */
  String workspaceImage() {
    Estate e = estate;
    return e == null ? null : e.workspaceImage();
  }

  /** Stop the node's timers; the process ending does the same. For a suite's teardown. */
  void close() {
    timer.shutdownNow();
  }

  // --- the frames ---------------------------------------------------------------------------------

  /**
   * The server's list. Containers of rows it does not own go at once, then the {@code inventory}
   * says what is left; the workspace image is pulled when it is not here (so the probe, the health
   * check and the operator's login command all find it), and the login is probed.
   */
  private void onEstate(Estate e, RunnerSession session) {
    estate = e;
    if (decommissioning) {
      return;
    }
    Set<Long> owned = Set.copyOf(e.owned());
    sessionOwned = owned;
    LOG.infof(
        "workspaces-runner's estate: %d owned workspace(s), workspace image %s",
        owned.size(), e.workspaceImage());
    Set<Long> keep = new HashSet<>(owned);
    keep.addAll(unconfirmed);
    Set<String> keepIds = new LinkedHashSet<>();
    keep.forEach(row -> keepIds.add(Long.toString(row)));
    session.own().reconcile(keepIds);
    unconfirmed.removeAll(owned);
    // What the reconcile removed no longer holds a slot; the look releases it and says so.
    look(session, true);
    String image = e.workspaceImage();
    if (image != null && !image.isBlank()) {
      NodeImages.ensure(docker, session, image)
          .ifPresent(
              why ->
                  LOG.warnf(
                      "workspaces-runner could not pre-pull the workspace image %s: %s",
                      image, why));
    }
    probeLogin(session);
  }

  /**
   * The answer to the one outstanding {@code reserve}: the {@link WorkspaceLauncher}'s ladder,
   * then {@code launched} (the row holds a slot) or {@code launchFailed} (it holds none). A refused
   * take still calls {@code taken} then {@code released}: only {@code taken} tells the ledger its
   * {@code reserve} was answered, and without it the runner would never reserve again on this
   * socket.
   */
  private void onTake(Take take, RunnerSession session) {
    long rowId = take.rowId();
    String workId = Long.toString(rowId);
    inFlight.incrementAndGet();
    ReentrantLock lock = lockOf(rowId);
    lock.lock();
    try {
      WorkspacesRunnerMessage answer;
      if (decommissioning) {
        answer = new LaunchFailed(rowId, "this runner is being deleted");
      } else {
        unconfirmed.add(rowId);
        answer = launcher.launch(rowId, take.spec(), session);
      }
      session.send(answer);
      session.taken(workId);
      if (answer instanceof Launched) {
        lastRunning.put(rowId, true);
      } else {
        session.released(workId);
      }
    } finally {
      lock.unlock();
      inFlight.decrementAndGet();
    }
    sendInventory(session, true);
  }

  /**
   * {@code docker stop}, then {@code stopped}, and the slot is free. A row with no container here
   * is stopped already, and answered so. A docker that did not answer gets no answer either: the
   * server times out with the row unchanged rather than hearing one that is not true.
   */
  private void onStop(Stop stop, RunnerSession session) {
    long rowId = stop.rowId();
    String name = names.container(rowId);
    inFlight.incrementAndGet();
    ReentrantLock lock = lockOf(rowId);
    lock.lock();
    try {
      Optional<WorkspaceContainers.Seen> seen = containers.inspect(name);
      if (seen.isPresent()) {
        ContainersDriver.OpResult stopped = session.driver().stop(name, docker.deadline());
        if (!stopped.ok() && !WorkspaceContainers.absent(stopped.detail())) {
          LOG.warnf("workspaces-runner could not stop %s: %s", name, stopped.detail());
          return;
        }
      }
      lastRunning.put(rowId, false);
      session.send(new Stopped(rowId));
      session.released(Long.toString(rowId));
    } catch (RuntimeException e) {
      LOG.warnf("workspaces-runner could not stop workspace %d: %s", rowId, e.getMessage());
      return;
    } finally {
      lock.unlock();
      inFlight.decrementAndGet();
    }
    sendInventory(session, true);
  }

  /**
   * The container and its {@code -ws-<rowId>} volume removed, then {@code deleted}: nothing of the
   * row is left on the node. Either already gone counts as gone.
   */
  private void onDelete(Delete delete, RunnerSession session) {
    long rowId = delete.rowId();
    String name = names.container(rowId);
    String volume = volumes.workspace(rowId);
    inFlight.incrementAndGet();
    ReentrantLock lock = lockOf(rowId);
    lock.lock();
    try {
      ContainersDriver driver = session.driver();
      ContainersDriver.OpResult gone = driver.remove(name, docker.deadline());
      if (!gone.ok() && !WorkspaceContainers.absent(gone.detail())) {
        LOG.warnf("workspaces-runner could not remove %s: %s", name, gone.detail());
        return;
      }
      ContainersDriver.OpResult volumeGone = driver.removeVolume(volume, docker.deadline());
      if (!volumeGone.ok() && !WorkspaceContainers.absent(volumeGone.detail())) {
        LOG.warnf("workspaces-runner could not remove %s: %s", volume, volumeGone.detail());
        return;
      }
      lastRunning.remove(rowId);
      unconfirmed.remove(rowId);
      session.send(new Deleted(rowId));
      session.released(Long.toString(rowId));
    } finally {
      lock.unlock();
      inFlight.decrementAndGet();
    }
    sendInventory(session, true);
  }

  private void onHealthCheck(RunnerSession session) {
    if (decommissioning) {
      return;
    }
    session.send(healthCheck.run(session, workspaceImage()));
  }

  /**
   * Probe now, send {@code loginState}, and schedule the next: in {@link Timing#loginAbsentEvery}
   * while either login is ABSENT (an operator is probably logging in), else in {@link
   * Timing#loginPresentEvery}.
   */
  private void probeLogin(RunnerSession session) {
    if (decommissioning) {
      return;
    }
    LoginState state = loginProbe.probe(session.driver(), workspaceImage());
    session.send(state);
    boolean absent =
        state.claude() == LoginPresence.ABSENT || state.kimi() == LoginPresence.ABSENT;
    Duration next = absent ? timing.loginAbsentEvery() : timing.loginPresentEvery();
    synchronized (timer) {
      if (nextProbe != null) {
        nextProbe.cancel(false);
      }
      if (timer.isShutdown()) {
        return;
      }
      nextProbe =
          timer.schedule(
              () -> {
                RunnerSession s = this.session;
                if (s != null && live) {
                  probeLogin(s);
                }
              },
              next.toMillis(),
              TimeUnit.MILLISECONDS);
    }
  }

  // --- the node, as docker reports it -------------------------------------------------------------

  /**
   * Report every running workspace container as held — of {@code only}, when given. A listing that
   * failed reports nothing.
   */
  private void holdRunning(RunnerSession session, Set<String> only) {
    List<WorkspaceContainers.Seen> seen;
    try {
      seen = containers.list();
    } catch (RuntimeException e) {
      LOG.warnf("workspaces-runner could not list its workspaces: %s", e.getMessage());
      return;
    }
    for (WorkspaceContainers.Seen s : seen) {
      String workId = Long.toString(s.rowId());
      if (s.running() && (only == null || only.contains(workId))) {
        lastRunning.put(s.rowId(), true);
        session.taken(workId);
      }
    }
  }

  private void startWatch() {
    synchronized (timer) {
      if (watch != null || timer.isShutdown()) {
        return;
      }
      long every = timing.watchEvery().toMillis();
      watch = timer.scheduleWithFixedDelay(this::watchOnce, every, every, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * One look at the node. A row that was running and is not, with no operation of ours on it, is
   * {@code exited} with docker's exit code (and silently released when its container is gone
   * altogether: there is no exit code to report, and the {@code inventory} says the rest). The
   * {@code inventory} goes out when anything changed.
   */
  private void watchOnce() {
    RunnerSession s = session;
    if (s == null || !live || decommissioning) {
      return;
    }
    look(s, false);
  }

  /**
   * The watch's look, and the {@code estate}'s after its reconcile; the {@code inventory} goes out
   * when {@code always}, else when anything changed.
   */
  private void look(RunnerSession s, boolean always) {
    try {
      List<WorkspaceContainers.Seen> now = containers.list();
      Map<Long, WorkspaceContainers.Seen> byRow = new HashMap<>();
      now.forEach(seen -> byRow.put(seen.rowId(), seen));
      Set<Long> candidates = new HashSet<>();
      lastRunning.forEach((row, running) -> candidates.add(row));
      s.held().forEach(workId -> rowId(workId).ifPresent(candidates::add));
      now.forEach(seen -> candidates.add(seen.rowId()));
      List<String> held = s.held();
      for (long row : candidates) {
        ReentrantLock lock = lockOf(row);
        if (!lock.tryLock()) {
          continue; // a take, stop or delete of ours is under way; it says what happened
        }
        try {
          WorkspaceContainers.Seen seen = byRow.get(row);
          boolean running = seen != null && seen.running();
          boolean was = Boolean.TRUE.equals(lastRunning.get(row));
          if (!running) {
            if (was && seen != null) {
              LOG.infof(
                  "workspaces-runner's workspace %d exited by itself (exit code %d)",
                  row, seen.exitCode());
              s.send(new Exited(row, seen.exitCode()));
            }
            if (held.contains(Long.toString(row))) {
              s.released(Long.toString(row));
            }
          }
          if (seen == null) {
            lastRunning.remove(row);
          } else {
            lastRunning.put(row, running);
          }
        } finally {
          lock.unlock();
        }
      }
      sendInventory(s, always);
    } catch (RuntimeException e) {
      LOG.debugf("workspaces-runner could not look at its workspaces: %s", e.getMessage());
    }
  }

  /**
   * What of the workspaces is on this node, read from docker: sent when {@code always}, else only
   * when it differs from the last one sent. A listing docker did not answer is never sent — read
   * as empty, it would tell the server every workspace is gone.
   */
  private void sendInventory(RunnerSession session, boolean always) {
    synchronized (inventoryLock) {
      Inventory inventory;
      try {
        List<HeldContainer> held = new ArrayList<>();
        for (WorkspaceContainers.Seen seen : containers.list()) {
          held.add(new HeldContainer(seen.rowId(), seen.running(), seen.specHash()));
        }
        held.sort(Comparator.comparingLong(HeldContainer::rowId));
        List<Long> rows = new ArrayList<>(containers.volumeRows(volumes));
        rows.sort(Comparator.naturalOrder());
        inventory = new Inventory(held, rows, volumes.dotClaude());
      } catch (RuntimeException e) {
        LOG.warnf("workspaces-runner sends no inventory: %s", e.getMessage());
        return;
      }
      if (!always && inventory.equals(lastInventory)) {
        return;
      }
      lastInventory = inventory;
      session.send(inventory);
    }
  }

  /** The rows whose volumes stay: this session's {@code owned} and the unconfirmed takes. */
  private Optional<Set<Long>> volumesToKeep() {
    Set<Long> owned = sessionOwned;
    if (owned == null) {
      return Optional.empty();
    }
    Set<Long> keep = new HashSet<>(owned);
    keep.addAll(unconfirmed);
    return Optional.of(keep);
  }

  private ReentrantLock lockOf(long rowId) {
    return rowLocks.computeIfAbsent(rowId, r -> new ReentrantLock());
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
