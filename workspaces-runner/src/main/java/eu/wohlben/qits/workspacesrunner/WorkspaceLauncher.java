package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import eu.wohlben.qits.workspacesrunner.protocol.LaunchFailed;
import eu.wohlben.qits.workspacesrunner.protocol.Launched;
import eu.wohlben.qits.workspacesrunner.protocol.Mount;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspaceSpec;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jboss.logging.Logger;

/**
 * A {@code take} on the node: the row's container brought to running, through
 * qits-containers-driver under the runner's label namespace. The ladder, cheapest rung first:
 *
 * <ol>
 *   <li>a <b>running</b> container of the row → {@code launched} with it, untouched;
 *   <li>an <b>existing</b> one whose {@code qits.workspaces.runner.spec} label equals the hash of
 *       this take's canonical spec → {@code docker start}: the same container, the same writable
 *       layer, what a stopped workspace being started again means; a <b>different</b> hash → the
 *       container is removed and created anew — <b>never its volume</b>, which holds the work;
 *   <li><b>none</b> → created.
 * </ol>
 *
 * <p>What a created container is, beyond the spec: the name {@code
 * qits-workspaces-runner-ws-<rowId>-<id8>}; the {@code /workspace} volume of the same name and the
 * node's agent home and caches ({@link NodeVolumes}), each made labelled before the run; uid
 * {@code 1000} ({@link #USER}; the image makes {@code /workspace}, {@code /claude-home} and {@code /caches}
 * 0777, so a container that is not root reads and writes them all); the default bridge; no restart
 * policy — the driver's {@link LifecyclePolicy#ephemeral} renders none, which is docker's {@code
 * --restart no}: a workspace that stopped stays stopped until qits-workspaces asks for it, and a
 * dockerd restart must not start one behind the server's back; and the spec's image pulled first
 * when it is absent, under the runner's own registry login.
 *
 * <p>Labels: the spec's own go in as {@code extraLabels}, where the driver refuses any key under
 * {@code qits.workspaces.runner.}; the runner's — {@code OwnResources.labels(rowId)} and the spec
 * hash — go in the driver's own-namespace argument, which is the only way in for them.
 *
 * <p>The spec's environment is never logged, here or anywhere the runner writes.
 */
final class WorkspaceLauncher {

  private static final Logger LOG = Logger.getLogger(WorkspaceLauncher.class);

  /** The suffix of the label a container's canonical spec hash rides under. */
  static final String SPEC_LABEL_SUFFIX = "spec";

  /**
   * Who every workspace container runs as: uid 1000. Not {@code 1000:1000}, which the brief asks
   * for: qits-containers-driver's user belt refuses a {@code :} (one value must not carry a group
   * nobody declared), so the group is the image's for that uid — the workspace image's own user.
   */
  static final String USER = "1000";

  /** The version of {@link #specHash}'s canonical form; a change to it is a change of the form. */
  private static final String CANONICAL_FORM = "workspace-spec/1";

  private final RunnerDocker docker;
  private final WorkspaceNames names;
  private final NodeVolumes volumes;
  private final WorkspaceContainers containers;
  private final NodeImages images;

  WorkspaceLauncher(
      RunnerDocker docker,
      WorkspaceNames names,
      NodeVolumes volumes,
      WorkspaceContainers containers,
      NodeImages images) {
    this.docker = docker;
    this.names = names;
    this.volumes = volumes;
    this.containers = containers;
    this.images = images;
  }

  /** {@code qits.workspaces.runner.spec}: an own-namespace label, so only the runner writes it. */
  static String specLabel(RunnerIdentity identity) {
    return identity.driverNamespace() + SPEC_LABEL_SUFFIX;
  }

  /** The answer to {@code take{rowId, spec}}: {@link Launched} or {@link LaunchFailed}. */
  WorkspacesRunnerMessage launch(long rowId, WorkspaceSpec spec, RunnerSession session) {
    String name = names.container(rowId);
    ContainersDriver driver = session.driver();
    Duration deadline = docker.deadline();
    String hash = specHash(spec);
    Optional<WorkspaceContainers.Seen> seen;
    try {
      seen = containers.inspect(name);
    } catch (RuntimeException e) {
      return failed(rowId, e.getMessage());
    }
    if (seen.isPresent() && seen.get().running()) {
      LOG.infof("workspaces-runner's workspace %d is already running as %s", rowId, name);
      return new Launched(rowId, seen.get().id());
    }
    if (seen.isPresent() && hash.equals(seen.get().specHash())) {
      ContainersDriver.OpResult started = driver.start(name, deadline);
      if (!started.ok()) {
        return failed(rowId, "docker could not start " + name + ": " + brief(started.detail()));
      }
      LOG.infof("workspaces-runner started workspace %d's container %s again", rowId, name);
      return new Launched(rowId, seen.get().id().isEmpty() ? name : seen.get().id());
    }
    if (seen.isPresent()) {
      // Another spec: the container goes, its volume stays — the work lives there, not here.
      ContainersDriver.OpResult gone = driver.remove(name, deadline);
      if (!gone.ok() && !WorkspaceContainers.absent(gone.detail())) {
        return failed(
            rowId, "docker could not remove " + name + "'s old container: " + brief(gone.detail()));
      }
      LOG.infof("workspaces-runner recreates workspace %d's container: its spec changed", rowId);
    }
    return create(rowId, name, spec, hash, session);
  }

  private WorkspacesRunnerMessage create(
      long rowId, String name, WorkspaceSpec spec, String hash, RunnerSession session) {
    ContainersDriver driver = session.driver();
    Duration deadline = docker.deadline();
    ContainerSpec container;
    try {
      container = containerSpec(rowId, spec);
    } catch (IllegalArgumentException refused) {
      return failed(rowId, "the workspace spec was refused: " + refused.getMessage());
    }
    Optional<String> notHere = images.ensure(session, spec.image());
    if (notHere.isPresent()) {
      return failed(rowId, "could not pull " + spec.image() + ": " + brief(notHere.get()));
    }
    List<Mount.Volume> needed = new ArrayList<>(List.of(Mount.Volume.WORKSPACE));
    spec.mounts().stream().map(Mount::volume).filter(v -> !needed.contains(v)).forEach(needed::add);
    for (Mount.Volume volume : needed) {
      ContainersDriver.OpResult made =
          volumes.ensure(driver, session.own(), volume, rowId, deadline);
      if (!made.ok()) {
        return failed(
            rowId,
            "docker could not create the volume "
                + volumes.name(volume, rowId)
                + ": "
                + brief(made.detail()));
      }
    }
    Map<String, String> labels = new LinkedHashMap<>(session.own().labels(Long.toString(rowId)));
    labels.put(specLabel(session.identity()), hash);
    ContainersDriver.Started started =
        driver.run(container, name, labels, LifecyclePolicy.ephemeral(null), deadline);
    if (!started.started()) {
      return failed(rowId, "docker could not run " + name + ": " + brief(started.detail()));
    }
    LOG.infof("workspaces-runner launched workspace %d as %s", rowId, name);
    return new Launched(rowId, started.containerId());
  }

  /** The driver's spec of a row's container: the take's, with the runner's fixed choices. */
  ContainerSpec containerSpec(long rowId, WorkspaceSpec spec) {
    ContainerSpec.Builder b =
        ContainerSpec.builder(spec.image())
            .network("bridge")
            .user(USER)
            .init(spec.init())
            .pullPolicy(ContainerSpec.PullPolicy.NEVER)
            .security(
                new ContainerSpec.SecurityPosture(
                    false,
                    false,
                    spec.memoryLimit(),
                    spec.memorySwapLimit(),
                    spec.pidsLimit(),
                    spec.cpus(),
                    spec.oomScoreAdj()));
    spec.env().forEach(b::env);
    spec.labels().forEach(b::label);
    for (Mount mount : spec.mounts()) {
      String volume = volumes.name(mount.volume(), rowId);
      if (mount.volume() == Mount.Volume.WORKSPACE) {
        b.mount(volume, mount.target());
      } else {
        // The node's, not this workload's: the driver keeps the two apart for what a removal reads.
        b.shared(volume, mount.target());
      }
    }
    return b.build();
  }

  /**
   * The sha256 of the spec's canonical form: every field, maps sorted by key and mounts by volume
   * then target — the frame's own order never matters — each value length-prefixed, so no value
   * can forge the separator of the next. The runner's fixed choices (user, network, restart) are
   * not in it: they are this version's, and {@link #CANONICAL_FORM} moves when they do.
   */
  static String specHash(WorkspaceSpec spec) {
    StringBuilder form = new StringBuilder(CANONICAL_FORM).append('\n');
    field(form, "image", spec.image());
    field(form, "init", Boolean.toString(spec.init()));
    new TreeMap<>(spec.env()).forEach((k, v) -> field(form, "env." + k, v));
    new TreeMap<>(spec.labels()).forEach((k, v) -> field(form, "label." + k, v));
    spec.mounts().stream()
        .map(m -> m.volume().name() + "\u0000" + m.target())
        .sorted()
        .forEach(m -> field(form, "mount", m));
    field(form, "memory", spec.memoryLimit());
    field(form, "memorySwap", spec.memorySwapLimit());
    field(form, "pids", spec.pidsLimit() == null ? null : spec.pidsLimit().toString());
    field(form, "cpus", spec.cpus());
    field(form, "oomScoreAdj", spec.oomScoreAdj() == null ? null : spec.oomScoreAdj().toString());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(form.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("this JVM has no SHA-256", e);
    }
  }

  private static void field(StringBuilder form, String key, String value) {
    form.append(key).append('=');
    if (value == null) {
      form.append("-\n");
    } else {
      form.append(value.length()).append(':').append(value).append('\n');
    }
  }

  private static LaunchFailed failed(long rowId, String detail) {
    LOG.warnf("workspaces-runner could not launch workspace %d: %s", rowId, detail);
    return new LaunchFailed(rowId, detail);
  }

  static String brief(String text) {
    String s = text == null ? "" : text.strip();
    return s.length() > 400 ? "…" + s.substring(s.length() - 400) : s;
  }
}
