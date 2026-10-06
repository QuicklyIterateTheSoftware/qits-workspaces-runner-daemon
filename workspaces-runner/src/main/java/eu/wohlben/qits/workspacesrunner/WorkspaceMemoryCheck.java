package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import eu.wohlben.qits.workspacesrunner.health.RunnerHealthCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The {@code workspaceMemory} health check: whether a workspace was OOM-killed at its cgroup
 * memory cap, and whether the node's running workspaces could together outgrow it. Neither is a
 * broken node — a workspace that overran its own limit is the workspace's affair, and an operator
 * may size things knowing every workspace will not peak at once — so, exactly like {@code login},
 * <b>this check is always {@code ok: true}</b>. It exists to put in front of a person what a
 * workspace's own {@code /sys/fs/cgroup/memory.events} already knew and nothing on the host
 * surfaced: an agent session OOM-killed at the cap, with the host none the wiser.
 *
 * <p><b>Per running workspace</b>, one {@code docker exec} reads {@code memory.max}, {@code
 * memory.current} and {@code memory.events} in a single call — the bounded exec budget this check
 * is given does not stretch to three. The exec's last argument repeats the container's name as
 * {@code sh -c}'s optional command-name positional ({@code sh -c SCRIPT <name>} sets its {@code
 * $0}, otherwise unused here): a free, harmless way to tell one workspace's exec from another's in
 * a node's own process list, and the same thing {@code FakeDocker}'s test seam keys its scripted
 * answers on. A container with no cgroup v2 unified hierarchy (v1), one that stops between the
 * listing and the exec, or one docker simply does not answer for, is reported with every number
 * null and a {@code note} saying why — never a thrown exception, and never a reason to fail the
 * check. The row id and container name are {@link WorkspaceNames}' derived spelling, never a
 * frame's.
 *
 * <p><b>Node-level</b>: {@code MemTotal} and {@code SwapTotal} from {@code /proc/meminfo} (the
 * runner's own container shares the host's, so this reads the node's, not the runner's cgroup),
 * the node's slots (informational only here), and {@code overcommitted}. <b>The memory limit is
 * not a runner setting this reads</b> — unlike an earlier shape of this check, it draws nothing
 * from an announced, node-wide limit, because that is moving to a per-row field an admin edits in
 * qits-workspaces' own UI (as qits-ci already does), so a runner-wide figure would soon be fiction.
 * Instead {@code overcommitted} is <b>the sum of the running workspaces' own observed {@code
 * memory.max} compared with {@code MemTotal}</b> — what is actually asked of the node right now,
 * read off the containers themselves rather than a config this runner no longer owns a copy of. A
 * workspace reporting {@code "max"} (no cgroup cap at all) contributes nothing to the sum, the same
 * as one this could not read: both are "cannot tell", not zero, so the sum undercounts a node with
 * any uncapped workspace rather than overstating one. {@code overcommitted} is null when nothing
 * could be summed (no running workspace, or none with a numeric cap) or {@code MemTotal} itself
 * could not be read — never a guess.
 *
 * <p><b>Bounded, not unbounded.</b> A node with many workspaces could spend its whole {@link
 * #deadline()} on execs alone; past {@link #DEADLINE_CALLS} worth of {@link RunnerDocker#deadline()}
 * elapsed in one run, the remaining containers are reported null with a {@code note} instead of
 * risking the whole check timing out — which, unlike a workspace's own OOM kill, would be read as a
 * broken node and must never happen here.
 */
final class WorkspaceMemoryCheck implements RunnerHealthCheck {

  /** Where the host's memory and swap totals are read; the runner's own container shares them. */
  static final Path MEMINFO = Path.of("/proc/meminfo");

  /** The exec budget this check spends before it starts skipping the rest, in {@code docker.deadline()}s. */
  private static final int DEADLINE_CALLS = 20;

  /** Separates the three files' output in one exec's stdout; none of the three ever contains it. */
  static final String DELIMITER = "@qits-cgroup@";

  /** {@code cat} each cgroup v2 file in turn, a missing one simply contributing nothing. */
  private static final String SCRIPT =
      "cat /sys/fs/cgroup/memory.max 2>/dev/null; printf '%s' '"
          + DELIMITER
          + "'; cat /sys/fs/cgroup/memory.current 2>/dev/null; printf '%s' '"
          + DELIMITER
          + "'; cat /sys/fs/cgroup/memory.events 2>/dev/null";

  private final RunnerDocker docker;
  private final WorkspaceContainers containers;
  private final WorkspaceNames names;
  private final Supplier<Integer> slots;
  private final Clock clock;
  private final Duration deadline;
  private final Path meminfo;

  WorkspaceMemoryCheck(
      RunnerDocker docker,
      WorkspaceContainers containers,
      WorkspaceNames names,
      Supplier<Integer> slots,
      Clock clock) {
    this(docker, containers, names, slots, clock, MEMINFO);
  }

  /** The same, reading the node's totals from {@code meminfo} — a suite's own file, in tests. */
  WorkspaceMemoryCheck(
      RunnerDocker docker,
      WorkspaceContainers containers,
      WorkspaceNames names,
      Supplier<Integer> slots,
      Clock clock,
      Path meminfo) {
    this.docker = docker;
    this.containers = containers;
    this.names = names;
    this.slots = slots;
    this.clock = clock;
    this.meminfo = meminfo;
    this.deadline = docker.deadline().multipliedBy(DEADLINE_CALLS);
  }

  @Override
  public String name() {
    return "workspaceMemory";
  }

  @Override
  public Duration deadline() {
    return deadline;
  }

  @Override
  public Result run(RunnerSession ignored) {
    List<WorkspaceContainers.Seen> running;
    try {
      running =
          containers.list().stream()
              .filter(WorkspaceContainers.Seen::running)
              .sorted(Comparator.comparingLong(WorkspaceContainers.Seen::rowId))
              .toList();
    } catch (RuntimeException notListable) {
      // A listing docker did not answer is nothing this always-ok check can act on; the next
      // nodeInventory or inventory still says so.
      running = List.of();
    }

    Instant giveUpAt = clock.instant().plus(deadline).minus(docker.deadline());
    List<Map<String, Object>> workspaces = new ArrayList<>();
    for (WorkspaceContainers.Seen seen : running) {
      workspaces.add(clock.instant().isAfter(giveUpAt) ? skipped(seen) : readOne(seen));
    }

    Map<String, Object> node = node(workspaces);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("workspaces", workspaces);
    data.put("node", node);
    return Result.ok(detail(workspaces, node), data);
  }

  // --- the per-workspace read ----------------------------------------------------------------------

  private List<String> execArgv(String container) {
    return List.of(docker.binary(), "exec", container, "sh", "-c", SCRIPT, container);
  }

  private Map<String, Object> blank(WorkspaceContainers.Seen seen) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("rowId", seen.rowId());
    entry.put("container", names.container(seen.rowId()));
    entry.put("memoryMax", null);
    entry.put("memoryCurrent", null);
    entry.put("oomKill", null);
    entry.put("oom", null);
    return entry;
  }

  private Map<String, Object> skipped(WorkspaceContainers.Seen seen) {
    Map<String, Object> entry = blank(seen);
    entry.put("note", "skipped: over the check's own time budget");
    return entry;
  }

  private Map<String, Object> readOne(WorkspaceContainers.Seen seen) {
    Map<String, Object> entry = blank(seen);
    String container = (String) entry.get("container");
    RunnerDocker.Result answered = docker.run(execArgv(container));
    String out = answered.stdout() == null ? "" : answered.stdout();
    String[] parts = out.split(Pattern.quote(DELIMITER), -1);
    String maxRaw = parts.length > 0 ? parts[0].strip() : "";
    String curRaw = parts.length > 1 ? parts[1].strip() : "";
    String eventsRaw = parts.length > 2 ? parts[2].strip() : "";

    if (maxRaw.isEmpty() && curRaw.isEmpty() && eventsRaw.isEmpty()) {
      entry.put(
          "note",
          answered.ok()
              ? "no cgroup v2 memory files in this container"
              : "docker exec failed: " + answered.detail());
      return entry;
    }
    if (!maxRaw.isEmpty()) {
      entry.put("memoryMax", "max".equals(maxRaw) ? "max" : asLong(maxRaw));
    }
    if (!curRaw.isEmpty()) {
      entry.put("memoryCurrent", asLong(curRaw));
    }
    if (eventsRaw.isEmpty()) {
      entry.put("note", "no memory.events in this container (cgroup v1?)");
    } else {
      Map<String, Long> events = events(eventsRaw);
      entry.put("oomKill", events.get("oom_kill"));
      entry.put("oom", events.get("oom"));
    }
    return entry;
  }

  /** {@code memory.events}' {@code <key> <count>} lines, a line this runner cannot read left out. */
  private static Map<String, Long> events(String text) {
    Map<String, Long> values = new LinkedHashMap<>();
    for (String line : text.split("\n")) {
      String[] f = line.trim().split("\\s+");
      if (f.length == 2) {
        try {
          values.put(f[0], Long.parseLong(f[1]));
        } catch (NumberFormatException notANumber) {
          // left out; docker's own cgroup file misreading itself is not this check's to diagnose
        }
      }
    }
    return values;
  }

  private static Long asLong(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException notANumber) {
      return null;
    }
  }

  // --- the node-level read --------------------------------------------------------------------------

  private Map<String, Object> node(List<Map<String, Object>> workspaces) {
    Map<String, Long> totals;
    try {
      totals = meminfo(Files.readString(meminfo));
    } catch (IOException unreadable) {
      totals = Map.of();
    }
    Long memTotal = totals.get("MemTotal");

    Map<String, Object> node = new LinkedHashMap<>();
    node.put("memTotal", memTotal);
    node.put("swapTotal", totals.get("SwapTotal"));
    node.put("slots", slots.get());
    node.put("overcommitted", overcommitted(workspaces, memTotal));
    return node;
  }

  /**
   * {@code /proc/meminfo}'s {@code MemTotal:} and {@code SwapTotal:} lines, each {@code <n> kB}, in
   * bytes. A line this cannot parse — or the file this cannot read — is simply absent, never thrown:
   * a node-level fact is nothing to fail a workspace-level check over.
   */
  static Map<String, Long> meminfo(String content) {
    Map<String, Long> bytes = new LinkedHashMap<>();
    for (String line : content.split("\n")) {
      String[] f = line.trim().split("\\s+");
      if (f.length >= 2 && (f[0].equals("MemTotal:") || f[0].equals("SwapTotal:"))) {
        try {
          bytes.put(f[0].substring(0, f[0].length() - 1), Long.parseLong(f[1]) * 1024);
        } catch (NumberFormatException notANumber) {
          // left out; meminfo in a spelling this runner cannot read is not this check's to fail on
        }
      }
    }
    return bytes;
  }

  /**
   * The sum of the running workspaces' own {@code memoryMax} against {@code MemTotal} — see the
   * class javadoc for why this, and not a node-wide limit, is what overcommit is measured against.
   * Null without a {@code MemTotal} to compare against, or without a single numeric cap to sum: an
   * all-uncapped or all-unreadable node has nothing this can add up, and null says so rather than
   * guessing zero.
   */
  private static Boolean overcommitted(List<Map<String, Object>> workspaces, Long memTotal) {
    if (memTotal == null) {
      return null;
    }
    long sum = 0;
    boolean sawOne = false;
    for (Map<String, Object> ws : workspaces) {
      if (ws.get("memoryMax") instanceof Long cap) {
        sum += cap;
        sawOne = true;
      }
    }
    return sawOne ? sum > memTotal : null;
  }

  // --- the sentence ----------------------------------------------------------------------------------

  private static String detail(List<Map<String, Object>> workspaces, Map<String, Object> node) {
    List<String> oomNotes = new ArrayList<>();
    for (Map<String, Object> ws : workspaces) {
      if (ws.get("oomKill") instanceof Long oomKill && oomKill > 0) {
        oomNotes.add(
            "ws " + ws.get("rowId") + ": " + oomKill + " oom kill(s) at " + cap(ws.get("memoryMax"))
                + " cap");
      }
    }
    StringBuilder sentence = new StringBuilder();
    sentence.append(
        oomNotes.isEmpty()
            ? workspaces.size() + " workspace(s), no oom kills"
            : String.join("; ", oomNotes));
    if (Boolean.TRUE.equals(node.get("overcommitted")) && node.get("memTotal") instanceof Long total) {
      long sum = 0;
      for (Map<String, Object> ws : workspaces) {
        if (ws.get("memoryMax") instanceof Long cap) {
          sum += cap;
        }
      }
      sentence
          .append("; node overcommitted: running caps sum to ")
          .append(humanize(sum))
          .append(" > ")
          .append(humanize(total))
          .append(" MemTotal");
    }
    return sentence.toString();
  }

  private static String cap(Object memoryMax) {
    if (memoryMax instanceof Long bytes) {
      return humanize(bytes);
    }
    return "max".equals(memoryMax) ? "no" : "an unknown";
  }

  /** Binary units, one decimal — {@code 4.0 GiB}, the same spelling docker's sizes read as. */
  private static String humanize(long bytes) {
    String[] units = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};
    double value = bytes;
    int unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit++;
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
  }
}
