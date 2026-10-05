package eu.wohlben.qits.workspacesrunner.protocol;

import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.WireMaps;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol.Field;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The workspace frames over {@code Map<String,Object>}: the {@link DomainCodec} qits-runner-protocol's
 * {@code RunnerCodec} delegates every non-lifecycle {@code type} to. Both peers use it — the runner
 * bridging the map with a Vert.x {@code JsonObject}, qits-workspaces with Jackson — so a field is
 * spelled in exactly one place.
 *
 * <p>Build it into a socket codec as {@code new RunnerCodec<>(WorkspacesRunnerProtocol.VOCABULARY,
 * new WorkspacesRunnerCodec())}.
 *
 * <p><b>Tolerant where it is safe, strict where it is not.</b> Every field is read through {@link
 * WireMaps}, so unknown fields are ignored and either box for a number is fine. A missing row id, a
 * missing spec image or an unknown mount volume is a refusal — {@code RunnerCodec} reports it as
 * MALFORMED and the receiver drops the frame — and so is a missing {@code owned} on an {@link
 * Estate} or a missing list on an {@link Inventory}: those lists are desired and observed state, and
 * an absence read as "empty" would remove every workspace on a node. A login presence nobody here
 * knows reads as {@link LoginPresence#UNKNOWN}, because it is a report, not an instruction.
 *
 * <p>Every frame writes {@code type} first and its fields in record order; a nullable field is
 * written as {@code null} rather than left out, as qits-ci-runner-protocol's {@code WorkloadSpec}
 * is.
 */
public final class WorkspacesRunnerCodec implements DomainCodec<WorkspacesRunnerMessage> {

  @Override
  public Class<WorkspacesRunnerMessage> messageType() {
    return WorkspacesRunnerMessage.class;
  }

  @Override
  public Set<String> types() {
    return Type.ALL;
  }

  @Override
  public Map<String, Object> encode(WorkspacesRunnerMessage message) {
    Map<String, Object> map = new LinkedHashMap<>();
    switch (message) {
      case Estate m -> {
        map.put(Field.TYPE, Type.ESTATE);
        map.put(Field.OWNED, new ArrayList<>(m.owned()));
        map.put(Field.WORKSPACE_IMAGE, m.workspaceImage());
      }
      case Take m -> {
        map.put(Field.TYPE, Type.TAKE);
        map.put(Field.ROW_ID, m.rowId());
        map.put(Field.SPEC, m.spec() == null ? null : spec(m.spec()));
      }
      case Stop m -> {
        map.put(Field.TYPE, Type.STOP);
        map.put(Field.ROW_ID, m.rowId());
      }
      case Delete m -> {
        map.put(Field.TYPE, Type.DELETE);
        map.put(Field.ROW_ID, m.rowId());
      }
      case ProbeLogin _ -> map.put(Field.TYPE, Type.PROBE_LOGIN);
      case HealthCheck _ -> map.put(Field.TYPE, Type.HEALTH_CHECK);
      case Inventory m -> {
        map.put(Field.TYPE, Type.INVENTORY);
        List<Object> containers = new ArrayList<>();
        for (HeldContainer c : m.containers()) {
          Map<String, Object> held = new LinkedHashMap<>();
          held.put(Field.ROW_ID, c.rowId());
          held.put(Field.RUNNING, c.running());
          held.put(Field.SPEC_HASH, c.specHash());
          containers.add(held);
        }
        map.put(Field.CONTAINERS, containers);
        map.put(Field.VOLUMES, new ArrayList<>(m.volumes()));
        map.put(Field.DOT_CLAUDE_VOLUME, m.dotClaudeVolume());
      }
      case Launched m -> {
        map.put(Field.TYPE, Type.LAUNCHED);
        map.put(Field.ROW_ID, m.rowId());
        map.put(Field.CONTAINER_ID, m.containerId());
      }
      case LaunchFailed m -> {
        map.put(Field.TYPE, Type.LAUNCH_FAILED);
        map.put(Field.ROW_ID, m.rowId());
        map.put(Field.DETAIL, m.detail());
      }
      case Stopped m -> {
        map.put(Field.TYPE, Type.STOPPED);
        map.put(Field.ROW_ID, m.rowId());
      }
      case Deleted m -> {
        map.put(Field.TYPE, Type.DELETED);
        map.put(Field.ROW_ID, m.rowId());
      }
      case Exited m -> {
        map.put(Field.TYPE, Type.EXITED);
        map.put(Field.ROW_ID, m.rowId());
        map.put(Field.EXIT_CODE, m.exitCode());
      }
      case LoginState m -> {
        map.put(Field.TYPE, Type.LOGIN_STATE);
        map.put(Field.CLAUDE, m.claude().name());
        map.put(Field.KIMI, m.kimi().name());
        map.put(Field.CHECKED_AT, m.checkedAt());
      }
      case HealthChecked m -> {
        map.put(Field.TYPE, Type.HEALTH_CHECKED);
        map.put(Field.OK, m.ok());
        map.put(Field.DETAIL, m.detail());
      }
    }
    return map;
  }

  @Override
  public WorkspacesRunnerMessage decode(String type, Map<String, Object> map) {
    return switch (type) {
      case Type.ESTATE ->
          new Estate(requiredLongs(map, Field.OWNED), WireMaps.str(map, Field.WORKSPACE_IMAGE));
      case Type.TAKE -> new Take(rowId(map), spec(WireMaps.object(map, Field.SPEC)));
      case Type.STOP -> new Stop(rowId(map));
      case Type.DELETE -> new Delete(rowId(map));
      case Type.PROBE_LOGIN -> new ProbeLogin();
      case Type.HEALTH_CHECK -> new HealthCheck();
      case Type.INVENTORY ->
          new Inventory(
              held(map),
              requiredLongs(map, Field.VOLUMES),
              WireMaps.str(map, Field.DOT_CLAUDE_VOLUME));
      case Type.LAUNCHED -> new Launched(rowId(map), WireMaps.str(map, Field.CONTAINER_ID));
      case Type.LAUNCH_FAILED -> new LaunchFailed(rowId(map), WireMaps.str(map, Field.DETAIL));
      case Type.STOPPED -> new Stopped(rowId(map));
      case Type.DELETED -> new Deleted(rowId(map));
      case Type.EXITED -> new Exited(rowId(map), WireMaps.intVal(map, Field.EXIT_CODE));
      case Type.LOGIN_STATE ->
          new LoginState(
              LoginPresence.fromWire(WireMaps.str(map, Field.CLAUDE)),
              LoginPresence.fromWire(WireMaps.str(map, Field.KIMI)),
              WireMaps.str(map, Field.CHECKED_AT));
      case Type.HEALTH_CHECKED ->
          new HealthChecked(WireMaps.boolVal(map, Field.OK), WireMaps.str(map, Field.DETAIL));
      default -> throw new IllegalArgumentException("not a workspaces runner frame: " + type);
    };
  }

  // --- the spec ----------------------------------------------------------------------------------

  private static Map<String, Object> spec(WorkspaceSpec s) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(Field.IMAGE, s.image());
    map.put(Field.ENV, new LinkedHashMap<>(s.env()));
    List<Object> mounts = new ArrayList<>();
    for (Mount mount : s.mounts()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put(Field.VOLUME, mount.volume().name());
      m.put(Field.TARGET, mount.target());
      mounts.add(m);
    }
    map.put(Field.MOUNTS, mounts);
    map.put(Field.LABELS, new LinkedHashMap<>(s.labels()));
    map.put(Field.MEMORY_LIMIT, s.memoryLimit());
    map.put(Field.MEMORY_SWAP_LIMIT, s.memorySwapLimit());
    map.put(Field.PIDS_LIMIT, s.pidsLimit());
    map.put(Field.CPUS, s.cpus());
    map.put(Field.OOM_SCORE_ADJ, s.oomScoreAdj());
    map.put(Field.INIT, s.init());
    return map;
  }

  private static WorkspaceSpec spec(Map<String, Object> map) {
    if (map == null) {
      throw new IllegalArgumentException(Field.SPEC + " is missing");
    }
    Number pids = WireMaps.number(map, Field.PIDS_LIMIT);
    Number oom = WireMaps.number(map, Field.OOM_SCORE_ADJ);
    return new WorkspaceSpec(
        WireMaps.str(map, Field.IMAGE),
        WireMaps.stringMap(map, Field.ENV),
        mounts(map),
        WireMaps.stringMap(map, Field.LABELS),
        WireMaps.str(map, Field.MEMORY_LIMIT),
        WireMaps.str(map, Field.MEMORY_SWAP_LIMIT),
        pids == null ? null : pids.longValue(),
        WireMaps.str(map, Field.CPUS),
        oom == null ? null : oom.intValue(),
        WireMaps.boolVal(map, Field.INIT));
  }

  private static List<Mount> mounts(Map<String, Object> map) {
    List<Mount> mounts = new ArrayList<>();
    for (Map<String, Object> m : objects(map, Field.MOUNTS, false)) {
      String volume = WireMaps.str(m, Field.VOLUME);
      Mount.Volume which;
      try {
        which = Mount.Volume.valueOf(volume == null ? "" : volume);
      } catch (IllegalArgumentException unknown) {
        throw new IllegalArgumentException("unknown mount volume: " + volume);
      }
      mounts.add(new Mount(which, WireMaps.str(m, Field.TARGET)));
    }
    return mounts;
  }

  private static List<HeldContainer> held(Map<String, Object> map) {
    List<HeldContainer> held = new ArrayList<>();
    for (Map<String, Object> c : objects(map, Field.CONTAINERS, true)) {
      held.add(
          new HeldContainer(
              rowId(c), WireMaps.boolVal(c, Field.RUNNING), WireMaps.str(c, Field.SPEC_HASH)));
    }
    return held;
  }

  // --- readers -----------------------------------------------------------------------------------

  /** The {@code rowId} field: required, a number. */
  private static long rowId(Map<String, Object> map) {
    Number value = WireMaps.number(map, Field.ROW_ID);
    if (value == null) {
      throw new IllegalArgumentException(Field.ROW_ID + " is missing");
    }
    return value.longValue();
  }

  /** A list of row ids that must be present — empty is a statement, absent is not one. */
  private static List<Long> requiredLongs(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      throw new IllegalArgumentException(key + " is missing");
    }
    if (!(value instanceof Iterable<?> items) || value instanceof Map<?, ?>) {
      throw new IllegalArgumentException("'" + key + "' is not a list");
    }
    List<Long> ids = new ArrayList<>();
    for (Object item : items) {
      if (!(item instanceof Number number)) {
        throw new IllegalArgumentException("'" + key + "' holds a value that is not a row id");
      }
      ids.add(number.longValue());
    }
    return ids;
  }

  /** A list of nested objects, each a {@code Map} or a Vert.x {@code JsonObject}. */
  private static List<Map<String, Object>> objects(
      Map<String, Object> map, String key, boolean required) {
    Object value = map.get(key);
    if (value == null) {
      if (required) {
        throw new IllegalArgumentException(key + " is missing");
      }
      return List.of();
    }
    if (!(value instanceof Iterable<?> items) || value instanceof Map<?, ?>) {
      throw new IllegalArgumentException("'" + key + "' is not a list");
    }
    List<Map<String, Object>> objects = new ArrayList<>();
    for (Object item : items) {
      // WireMaps reads a nested object out of a map by key; wrapping the item reuses that reading,
      // so a JsonObject inside a JsonArray is accepted exactly as a nested one is.
      Map<String, Object> holder = new LinkedHashMap<>();
      holder.put(key, item);
      Map<String, Object> object = WireMaps.object(holder, key);
      if (object == null) {
        throw new IllegalArgumentException("'" + key + "' holds a null");
      }
      objects.add(object);
    }
    return objects;
  }
}
