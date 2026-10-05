package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import eu.wohlben.qits.workspacesrunner.health.RunnerHealthCheck;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code nodeInventory} health check: what docker says about this runner's objects on its node
 * — the facts an operator would otherwise read with {@code docker ps}/{@code docker volume ls} on
 * the node by hand (ids, states, start and finish times, exit codes, volume creation times). Its
 * {@code data}:
 *
 * <pre>
 *   containers:      [{name, id, rowId|null, state, startedAt|null, finishedAt|null, exitCode|null, image}]
 *   volumes:         [{name, rowId|null, createdAt|null}]
 *   runnerContainer: {name, id, version, startedAt|null} | null
 * </pre>
 *
 * <p><b>Read-only.</b> Every call here is {@code ps}, {@code volume ls}, {@code inspect} or {@code
 * volume inspect}, through the same bounded process seam as the domain's other reads. Nothing is
 * created, started, stopped or removed for it.
 *
 * <p><b>The runner's own labels only</b>: {@code qits.workspaces.runner=<id>} for the containers and
 * volumes, {@code qits.workspaces.runner.process=<id>} for the runner's own container. The node is
 * shared with a CI runner and a person's things, and those are not this runner's to describe.
 *
 * <p><b>What could be read, and nothing invented.</b> A listing or an inspect docker did not answer
 * leaves its objects out, fails the check and says so in its {@code detail}; an object that went
 * between the listing and its inspect is simply not in the data. Unlike the {@code inventory}, the
 * partial data is still reported: it is read by a person, and nothing acts on it.
 *
 * <p>The container template is the map path's ({@code .Id}, as {@link WorkspaceContainers}'), so
 * the times are docker's RFC 3339 strings exactly as its JSON carries them.
 */
final class NodeInventoryCheck implements RunnerHealthCheck {

  /** Docker's "never" for a start or finish time. */
  static final String ZERO_TIME = "0001-01-01T00:00:00Z";

  private final RunnerIdentity identity;
  private final String runnerId;
  private final RunnerDocker docker;
  private final String containerFormat;
  private final String volumeFormat;

  NodeInventoryCheck(RunnerIdentity identity, String runnerId, RunnerDocker docker) {
    this.identity = identity;
    this.runnerId = LifecycleArgv.requireRunnerId(runnerId);
    this.docker = docker;
    this.containerFormat =
        "{{.Id}}|{{.Name}}|{{.State.Status}}|{{.State.StartedAt}}|{{.State.FinishedAt}}"
            + "|{{.State.ExitCode}}|{{.Config.Image}}|{{index .Config.Labels \""
            + identity.workLabel()
            + "\"}}|{{index .Config.Labels \""
            + identity.versionLabel()
            + "\"}}";
    this.volumeFormat =
        "{{.Name}}|{{.CreatedAt}}|{{index .Labels \"" + identity.workLabel() + "\"}}";
  }

  /** One container as inspected: its data, and its version label (read for the runner's own). */
  private record Inspected(Map<String, Object> container, String version) {

    String name() {
      return (String) container.get("name");
    }
  }

  @Override
  public String name() {
    return "nodeInventory";
  }

  @Override
  public Result run(RunnerSession session) {
    List<String> errors = new ArrayList<>();

    List<Map<String, Object>> containers = new ArrayList<>();
    for (String id : list(ownerFilter(), errors, "containers")) {
      inspectContainer(id, errors).ifPresent(seen -> containers.add(seen.container()));
    }
    containers.sort(Comparator.comparing(c -> String.valueOf(c.get("name"))));

    List<Map<String, Object>> volumes = new ArrayList<>();
    for (String name : listVolumes(errors)) {
      inspectVolume(name, errors).ifPresent(volumes::add);
    }
    volumes.sort(Comparator.comparing(v -> String.valueOf(v.get("name"))));

    Map<String, Object> runner = null;
    String self = identity.containerName(runnerId, WorkspacesRunnerBinary.VERSION);
    for (String id : list(processFilter(), errors, "runner containers")) {
      Optional<Inspected> seen = inspectContainer(id, errors);
      if (seen.isPresent() && self.equals(seen.get().name())) {
        Map<String, Object> c = seen.get().container();
        runner = new LinkedHashMap<>();
        runner.put("name", c.get("name"));
        runner.put("id", c.get("id"));
        runner.put("version", seen.get().version());
        runner.put("startedAt", c.get("startedAt"));
      }
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("containers", containers);
    data.put("volumes", volumes);
    data.put("runnerContainer", runner);
    String counted =
        containers.size()
            + " container(s), "
            + volumes.size()
            + " volume(s), "
            + (runner == null ? "no runner container" : "runner container " + runner.get("name"));
    return errors.isEmpty()
        ? Result.ok(counted, data)
        : Result.failed(String.join("; ", errors) + " (read: " + counted + ")", data);
  }

  // --- the listings ------------------------------------------------------------------------------

  private String ownerFilter() {
    return "label=" + identity.ownerLabel() + "=" + runnerId;
  }

  private String processFilter() {
    return "label=" + identity.processLabel() + "=" + runnerId;
  }

  /** {@code docker ps -aq} under one of the runner's own label filters. */
  private List<String> list(String filter, List<String> errors, String what) {
    RunnerDocker.Result listed =
        docker.run(List.of(docker.binary(), "ps", "-aq", "--filter", filter));
    if (!listed.ok()) {
      errors.add("could not list the " + what + ": " + listed.detail());
      return List.of();
    }
    return words(listed.stdout());
  }

  private List<String> listVolumes(List<String> errors) {
    RunnerDocker.Result listed =
        docker.run(List.of(docker.binary(), "volume", "ls", "-q", "--filter", ownerFilter()));
    if (!listed.ok()) {
      errors.add("could not list the volumes: " + listed.detail());
      return List.of();
    }
    return words(listed.stdout());
  }

  // --- the inspects ------------------------------------------------------------------------------

  private Optional<Inspected> inspectContainer(String id, List<String> errors) {
    Optional<String> line =
        inspect(
            List.of(docker.binary(), "inspect", "--type", "container", "--format", containerFormat),
            id,
            errors);
    if (line.isEmpty()) {
      return Optional.empty();
    }
    String[] f = line.get().split("\\|", -1);
    if (f.length < 9) {
      errors.add("docker answered an unreadable inspect of " + id);
      return Optional.empty();
    }
    String name = value(f[1]);
    if (name != null && name.startsWith("/")) {
      name = name.substring(1);
    }
    Map<String, Object> container = new LinkedHashMap<>();
    container.put("name", name);
    container.put("id", value(f[0]));
    container.put("rowId", rowId(value(f[7])));
    container.put("state", value(f[2]));
    container.put("startedAt", time(value(f[3])));
    container.put("finishedAt", time(value(f[4])));
    container.put("exitCode", exitCode(value(f[5])));
    container.put("image", value(f[6]));
    return Optional.of(new Inspected(container, value(f[8])));
  }

  private Optional<Map<String, Object>> inspectVolume(String name, List<String> errors) {
    Optional<String> line =
        inspect(List.of(docker.binary(), "volume", "inspect", "--format", volumeFormat), name, errors);
    if (line.isEmpty()) {
      return Optional.empty();
    }
    String[] f = line.get().split("\\|", -1);
    if (f.length < 3) {
      errors.add("docker answered an unreadable inspect of " + name);
      return Optional.empty();
    }
    String inspected = value(f[0]);
    Map<String, Object> volume = new LinkedHashMap<>();
    volume.put("name", inspected == null ? name : inspected);
    volume.put("rowId", rowId(value(f[2])));
    volume.put("createdAt", time(value(f[1])));
    return Optional.of(volume);
  }

  /**
   * One inspect's line, or empty: when the object went since its listing (no error), or when
   * docker did not answer (an error).
   */
  private Optional<String> inspect(List<String> argv, String object, List<String> errors) {
    List<String> call = new ArrayList<>(argv);
    try {
      call.add(LifecycleArgv.requireWorkId(object));
    } catch (IllegalArgumentException notAName) {
      errors.add("docker listed a name this runner does not read: " + object);
      return Optional.empty();
    }
    RunnerDocker.Result answered = docker.run(call);
    if (!answered.ok()) {
      if (!answered.timedOut() && WorkspaceContainers.absent(answered.detail())) {
        return Optional.empty();
      }
      errors.add("docker did not answer an inspect of " + object + ": " + answered.detail());
      return Optional.empty();
    }
    String line = answered.stdout().strip();
    if (line.isEmpty()) {
      errors.add("docker answered an empty inspect of " + object);
      return Optional.empty();
    }
    return Optional.of(line);
  }

  // --- the fields --------------------------------------------------------------------------------

  private static List<String> words(String stdout) {
    List<String> words = new ArrayList<>();
    for (String word : stdout.split("\\s+")) {
      if (!word.isBlank()) {
        words.add(word.strip());
      }
    }
    return words;
  }

  /** A field as docker printed it; null for nothing or the map path's {@code <no value>}. */
  private static String value(String field) {
    String v = field.strip();
    return v.isEmpty() || "<no value>".equals(v) ? null : v;
  }

  /** Docker's time as it printed it, or null for its zero time. */
  private static String time(String value) {
    return value == null || ZERO_TIME.equals(value) ? null : value;
  }

  private static Long rowId(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException notARow) {
      return null;
    }
  }

  /** The map path prints a JSON number, which may come back as {@code 137} or {@code 137.0}. */
  private static Integer exitCode(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException notAnInt) {
      try {
        return (int) Double.parseDouble(value);
      } catch (NumberFormatException notANumber) {
        return null;
      }
    }
  }
}
