package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.OwnResources;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What docker says about this runner's workspace containers: one by name, or all of them by the
 * runner's own labels. The reads behind the launch ladder, the {@code inventory}, the session
 * start's held list and the exit watch — one argv for all of them, so they can never disagree.
 *
 * <p><b>Its own inspect, not the driver's.</b> Three reasons, each a failure the driver's {@code
 * inspect} would have here: it carries no labels, and the ladder needs the spec label; it degrades
 * nothing — a docker that did not answer throws, and "could not read" never reads as "not there"
 * (the reason {@link OwnResources} lists through the process seam too); and it says {@code --type
 * container}, because a workspace's container and its {@code /workspace} volume share a name, and an
 * untyped {@code docker inspect} of a row whose container is gone answers the volume.
 *
 * <p>The template is the map path's ({@code .Id}, {@code index}): qits-containers-driver's {@code
 * OBSERVATION_FORMAT} measured that asking for {@code .Id} moves the whole template there, where
 * {@code index} of a missing label prints {@code <no value>} — read back here as "no label".
 */
final class WorkspaceContainers {

  /** One workspace container as docker reported it. */
  record Seen(String id, long rowId, String status, int exitCode, String specHash) {

    boolean running() {
      return "running".equals(status);
    }
  }

  private final RunnerIdentity identity;
  private final String runnerId;
  private final RunnerDocker docker;
  private final String format;

  WorkspaceContainers(RunnerIdentity identity, String runnerId, RunnerDocker docker) {
    this.identity = identity;
    this.runnerId = runnerId;
    this.docker = docker;
    this.format =
        "{{.Id}}|{{.State.Status}}|{{.State.ExitCode}}|{{index .Config.Labels \""
            + identity.workLabel()
            + "\"}}|{{index .Config.Labels \""
            + WorkspaceLauncher.specLabel(identity)
            + "\"}}";
  }

  /** The inspect every read here makes, of a name this runner derived or an id docker listed. */
  List<String> inspectArgv(String nameOrId) {
    return List.of(
        docker.binary(),
        "inspect",
        "--type",
        "container",
        "--format",
        format,
        LifecycleArgv.requireWorkId(nameOrId));
  }

  /**
   * The container under {@code name}, or empty when docker has none.
   *
   * @throws IllegalStateException when docker did not answer, or answered something unreadable
   */
  Optional<Seen> inspect(String name) {
    RunnerDocker.Result answered = docker.run(inspectArgv(name));
    if (!answered.ok()) {
      if (!answered.timedOut() && absent(answered.detail())) {
        return Optional.empty();
      }
      throw new IllegalStateException(
          "docker did not answer an inspect of " + name + ": " + answered.detail());
    }
    String line = answered.stdout().strip();
    if (line.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        parse(line)
            .orElseThrow(
                () -> new IllegalStateException("docker answered an unreadable inspect of " + name)));
  }

  /**
   * Every workspace container of this runner, running or not: listed by {@code
   * label=qits.workspaces.runner=<id>} and the work label, each inspected. A container that went
   * between the listing and its inspect is simply not in the answer.
   *
   * @throws IllegalStateException when docker did not answer the listing or an inspect — never
   *     read as an empty node
   */
  List<Seen> list() {
    RunnerDocker.Result listed =
        docker.run(
            List.of(
                docker.binary(),
                "ps",
                "-aq",
                "--filter",
                "label=" + identity.ownerLabel() + "=" + LifecycleArgv.requireRunnerId(runnerId),
                "--filter",
                "label=" + identity.workLabel()));
    if (!listed.ok()) {
      throw new IllegalStateException(
          "could not list the workspace containers: " + listed.detail());
    }
    List<Seen> seen = new ArrayList<>();
    for (String id : listed.stdout().split("\\s+")) {
      if (id.isBlank()) {
        continue;
      }
      RunnerDocker.Result answered = docker.run(inspectArgv(id.strip()));
      if (!answered.ok()) {
        if (!answered.timedOut() && absent(answered.detail())) {
          continue;
        }
        throw new IllegalStateException(
            "docker did not answer an inspect of " + id.strip() + ": " + answered.detail());
      }
      parse(answered.stdout().strip()).ifPresent(seen::add);
    }
    return seen;
  }

  /**
   * Every row whose workspace volume is on the node, by the runner's labels; each name is read
   * back through {@link NodeVolumes#rowOf}, so a volume this runner would not have named is no row.
   *
   * @throws IllegalStateException when docker did not answer
   */
  List<Long> volumeRows(NodeVolumes volumes) {
    RunnerDocker.Result listed =
        docker.run(
            List.of(
                docker.binary(),
                "volume",
                "ls",
                "-q",
                "--filter",
                "label=" + identity.ownerLabel() + "=" + LifecycleArgv.requireRunnerId(runnerId),
                "--filter",
                "label=" + identity.workLabel()));
    if (!listed.ok()) {
      throw new IllegalStateException("could not list the workspace volumes: " + listed.detail());
    }
    List<Long> rows = new ArrayList<>();
    for (String name : listed.stdout().split("\\s+")) {
      volumes.rowOf(name.strip()).ifPresent(rows::add);
    }
    return rows;
  }

  /** {@code <id>|<status>|<exitCode>|<rowId>|<specHash>}; empty when it is not one of ours. */
  static Optional<Seen> parse(String line) {
    String[] fields = line.split("\\|", -1);
    if (fields.length < 5) {
      return Optional.empty();
    }
    long rowId;
    try {
      rowId = Long.parseLong(value(fields[3]));
    } catch (NumberFormatException notARow) {
      return Optional.empty();
    }
    String hash = value(fields[4]);
    return Optional.of(
        new Seen(
            value(fields[0]),
            rowId,
            value(fields[1]).toLowerCase(Locale.ROOT),
            exitCode(value(fields[2])),
            hash.isEmpty() ? null : hash));
  }

  /** Docker's own words for "there is no such thing". */
  static boolean absent(String detail) {
    String text = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
    return text.contains("no such container")
        || text.contains("no such object")
        || text.contains("no such volume");
  }

  private static String value(String field) {
    String v = field.strip();
    return "<no value>".equals(v) ? "" : v;
  }

  /** The map path prints a JSON number, which may come back as {@code 137} or {@code 137.0}. */
  private static int exitCode(String value) {
    if (value.isEmpty()) {
      return -1;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException notAnInt) {
      try {
        return (int) Double.parseDouble(value);
      } catch (NumberFormatException notANumber) {
        return -1;
      }
    }
  }
}
