package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.pact.consumer.GoldenFiles;
import eu.wohlben.qits.workspacesrunner.RunnerProviderStates.Frame;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Records the runner's golden masters</b> (qits-950 A2): every frame it sends
 * qits-workspaces-service, per provider state — {@code golden-masters/} at the repository root,
 * which the platform publishes as {@code eu.wohlben.qits:qits-workspaces-runner-golden-masters} and
 * the service builds its message pact against. The estate's HTTP providers' machinery (qits-546),
 * with a frame in place of an answer:
 *
 * <ul>
 *   <li>For each {@link RunnerProviderStates#FRAMES} entry it runs the state, pulls the trigger and
 *       takes the frame as the runner wrote it on the socket — never built here, never re-encoded.
 *   <li>It freezes ids, instants, the runner's own version and the recording machine's facts
 *       ({@link GoldenFrames}) and renders {@code golden-masters/<state-slug>/frame-<type>.json};
 *       then {@code golden-masters/index.json}, whose operation entry carries {@code operationId:
 *       frame:<type>} where an HTTP provider's carries the openapi operation.
 *   <li>It <b>compares by default</b>, failing with a unified diff per differing file and on a
 *       committed {@code .json} no frame records any more; {@code -Dgolden.update=true} (or {@code
 *       QITS_GOLDEN_UPDATE=true}) rewrites, and deletes such stale files.
 * </ul>
 */
@EnabledOnOs(OS.LINUX)
class RunnerGoldenMasterRecordingTest {

  static final int FORMAT_VERSION = 1;

  /** The application the golden masters are published under ({@code contracts.application}). */
  static final String PROVIDER = "qits-workspaces-runner";

  /** Every frame here is one the runner sends: the service reads it. */
  static final String DIRECTION = "runner-to-service";

  /** An index operation that is a socket frame, not an HTTP answer. */
  static final String MESSAGE = "message";

  /** The operation key of a frame: {@code frame:<type>}. */
  static String operationId(String type) {
    return "frame:" + type;
  }

  /**
   * The recording machine's own numbers, per frame type: the node's memory, which the {@code
   * workspaceMemory} check reads off {@code /proc/meminfo} and no state can script.
   */
  private static final Map<String, Set<String>> HOST_FACTS =
      Map.of(
          "healthChecked",
          Set.of("$.checks[*].data.node.memTotal", "$.checks[*].data.node.swapTotal"));

  @TempDir Path work;

  @Test
  void goldenMastersMatchTheRunner() throws Exception {
    Path dir = GoldenFrames.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();

    Map<String, Map<String, Object>> indexStates = new TreeMap<>();
    Map<String, Map<String, Map<String, Object>>> indexOperations = new TreeMap<>();

    int n = 0;
    for (Frame frame : RunnerProviderStates.FRAMES) {
      String raw = RunnerProviderStates.produce(frame, work.resolve(Integer.toString(++n)));
      Map<String, Object> wire = new JsonObject(raw).getMap();
      assertEquals(frame.type(), wire.get("type"), raw);
      // A frame the protocol's own codec reads, as the service's does; it throws on one it cannot.
      RunnerProviderStates.CODEC.decode(wire);

      String slug = GoldenFrames.slug(frame.state());
      String op = operationId(frame.type());
      String file = slug + "/frame-" + frame.type() + ".json";
      Map<String, String> params = RunnerProviderStates.params(frame.state());
      GoldenFrames.Freezer freezer =
          new GoldenFrames.Freezer(HOST_FACTS.getOrDefault(frame.type(), Set.of()))
              .seed(params.values());
      Map<String, Object> frozenParams = new LinkedHashMap<>();
      params.forEach((k, v) -> frozenParams.put(k, freezer.freezeParam(v)));
      Object body = freezer.freeze(wire);

      Map<String, Object> state = indexStates.get(slug);
      if (state == null) {
        state = new LinkedHashMap<>();
        state.put("name", frame.state());
        state.put("slug", slug);
        state.put("params", frozenParams);
        state.put("dependsOn", List.of());
        indexStates.put(slug, state);
      }

      Map<String, Object> operation = new LinkedHashMap<>();
      operation.put("operationId", op);
      operation.put("kind", MESSAGE);
      operation.put("frame", frame.type());
      operation.put("direction", DIRECTION);
      Map<String, Object> trigger = new LinkedHashMap<>();
      trigger.put("kind", frame.trigger().kind());
      trigger.put(frame.trigger().kind(), frame.trigger().name());
      operation.put("trigger", trigger);
      operation.put("file", file);
      Map<String, Object> frozen = new LinkedHashMap<>();
      frozen.put("ids", List.copyOf(freezer.idPaths));
      frozen.put("instants", List.copyOf(freezer.instantPaths));
      frozen.put("strings", List.copyOf(freezer.stringPaths));
      if (!freezer.numberPaths.isEmpty()) {
        frozen.put("numbers", List.copyOf(freezer.numberPaths));
      }
      frozen.put("listFilteredTo", null);
      operation.put("frozen", frozen);
      if (indexOperations.computeIfAbsent(slug, k -> new TreeMap<>()).put(op, operation) != null) {
        failures.add("Duplicate frame " + file);
      }

      written.add(file);
      check(dir.resolve(file), GoldenFrames.render(body), update, failures);
    }

    Map<String, Object> index = new LinkedHashMap<>();
    index.put("formatVersion", FORMAT_VERSION);
    index.put("provider", PROVIDER);
    List<Object> states = new ArrayList<>();
    indexStates.forEach(
        (slug, state) -> {
          state.put("operations", List.copyOf(indexOperations.get(slug).values()));
          states.add(state);
        });
    index.put("states", states);
    written.add("index.json");
    check(dir.resolve("index.json"), GoldenFrames.render(index), update, failures);

    for (String stale : committedJson(dir)) {
      if (written.contains(stale)) {
        continue;
      }
      if (update) {
        Files.delete(dir.resolve(stale));
      } else {
        failures.add(
            dir.resolve(stale)
                + " is committed but no frame records it any more — rerun with"
                + " -Dgolden.update=true to delete it.");
      }
    }

    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  private static void check(Path golden, String actual, boolean update, List<String> failures) {
    String failure = GoldenFiles.check(golden, actual, update, UnaryOperator.identity());
    if (failure != null) {
      failures.add(failure);
    }
  }

  /** Every committed {@code .json} under {@code dir}, relative and {@code /}-separated. */
  private static List<String> committedJson(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".json"))
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted(Comparator.naturalOrder())
          .toList();
    }
  }
}
