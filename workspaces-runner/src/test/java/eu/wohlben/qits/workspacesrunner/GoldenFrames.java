package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The golden-master format's two mechanical halves for frames, on plain {@code Map}/{@code List}
 * trees (the runner has no Jackson, and its suite takes none on for this): freezing one frame so a
 * re-recording is byte-identical, and rendering a tree the way {@code JSON.stringify(value, null,
 * 2) + "\n"} does, so a consumer in any language can re-render a file byte for byte. The rules are
 * the estate's (qits-idp-service's {@code Freezer} and {@code GoldenJson}), plus the two values a
 * runner frame carries that no HTTP answer did:
 *
 * <ul>
 *   <li><b>Ids.</b> Every distinct UUID becomes {@code 00000000-0000-4000-8000-00000000000N},
 *       numbered by first appearance after the state's params ({@link #seed}).
 *   <li><b>Instants.</b> Every string that is an ISO-8601 instant becomes {@value #FROZEN_INSTANT}.
 *   <li><b>The runner's version.</b> {@link WorkspacesRunnerBinary#VERSION} is the tree's own
 *       release, so it moves on every release — {@code hello}'s {@code runnerVersion}, the runner
 *       container a health check reports. Each occurrence becomes {@value #FROZEN_VERSION}; were it
 *       kept, every release would republish golden masters that changed nothing.
 *   <li><b>Host facts.</b> A number the recording machine supplies rather than the state (the
 *       node's memory, read off {@code /proc/meminfo}) is named per frame and recorded as {@code
 *       0}.
 * </ul>
 *
 * <p>Each changed value's JSONPath ({@code $.a.b}, array elements as {@code [*]}) goes into the
 * index's {@code frozen} lists by what the whole value was: a UUID in {@code ids}, an instant in
 * {@code instants}, any other string in {@code strings}, a host fact in {@code numbers}.
 */
final class GoldenFrames {

  static final Pattern UUID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  static final Pattern INSTANT =
      Pattern.compile(
          "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})");

  static final String FROZEN_INSTANT = "2026-01-01T00:00:00Z";

  /** A calver of the estate's shape — 2026, January 1st, midnight — that no release carries. */
  static final String FROZEN_VERSION = "2026.101.0";

  private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private GoldenFrames() {}

  /** One frame's freezing: the mappings it built up, and every path it changed. */
  static final class Freezer {

    private final Map<String, String> ids = new HashMap<>();
    private final Set<String> hostFacts;
    final Set<String> idPaths = new LinkedHashSet<>();
    final Set<String> instantPaths = new LinkedHashSet<>();
    final Set<String> stringPaths = new LinkedHashSet<>();
    final Set<String> numberPaths = new LinkedHashSet<>();

    /**
     * @param hostFacts the paths whose numbers are the recording machine's, not the state's
     */
    Freezer(Set<String> hostFacts) {
      this.hostFacts = hostFacts;
    }

    /** Numbers each UUID in {@code values}, in order, ahead of anything the frame holds. */
    Freezer seed(Iterable<String> values) {
      values.forEach(this::freezeIds);
      return this;
    }

    /** A param value through the same mappings as the frame. */
    String freezeParam(String value) {
      return freezeIds(value).replace(WorkspacesRunnerBinary.VERSION, FROZEN_VERSION);
    }

    Object freeze(Object node) {
      return freeze(node, "$");
    }

    private Object freeze(Object node, String path) {
      if (hostFacts.contains(path)) {
        numberPaths.add(path);
        return 0;
      }
      if (node instanceof JsonObject object) {
        return freeze(object.getMap(), path);
      }
      if (node instanceof JsonArray array) {
        return freeze(array.getList(), path);
      }
      if (node instanceof String text) {
        return freezeText(text, path);
      }
      if (node instanceof List<?> list) {
        List<Object> out = new ArrayList<>();
        for (Object element : list) {
          out.add(freeze(element, path + "[*]"));
        }
        return out;
      }
      if (node instanceof Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> field : map.entrySet()) {
          String key = (String) field.getKey();
          if (UUID.matcher(key).find()) {
            throw new IllegalStateException(
                "A UUID in a key cannot be frozen: " + path + " / " + key);
          }
          out.put(key, freeze(field.getValue(), path + segment(key)));
        }
        return out;
      }
      return node;
    }

    private String freezeText(String value, String path) {
      if (INSTANT.matcher(value).matches()) {
        instantPaths.add(path);
        return FROZEN_INSTANT;
      }
      String result = freezeParam(value);
      if (result.equals(value)) {
        return result;
      }
      if (UUID.matcher(value).matches()) {
        idPaths.add(path);
      } else {
        stringPaths.add(path);
      }
      return result;
    }

    private String freezeIds(String value) {
      Matcher m = UUID.matcher(value);
      StringBuilder out = new StringBuilder();
      while (m.find()) {
        String original = m.group().toLowerCase(Locale.ROOT);
        String frozen = ids.computeIfAbsent(original, k -> frozenId(ids.size() + 1));
        m.appendReplacement(out, Matcher.quoteReplacement(frozen));
      }
      m.appendTail(out);
      return out.toString();
    }
  }

  /** {@code 00000000-0000-4000-8000-} plus {@code n} as 12 hex digits. */
  static String frozenId(int n) {
    return String.format("00000000-0000-4000-8000-%012x", n);
  }

  /** The state's slug: its name lower-cased, every run of other characters one {@code -}. */
  static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  /**
   * The repository root — the directory holding {@code pom.xml} and {@code
   * workspaces-runner/pom.xml} — found by walking up from the working directory.
   */
  static Path repositoryRoot() {
    Path dir = Path.of("").toAbsolutePath();
    for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
      if (Files.isRegularFile(candidate.resolve("pom.xml"))
          && Files.isRegularFile(candidate.resolve("workspaces-runner").resolve("pom.xml"))) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "No repository root (a directory with pom.xml and workspaces-runner/pom.xml) above " + dir);
  }

  // --- rendering ---------------------------------------------------------------------------------

  static String render(Object node) {
    StringBuilder out = new StringBuilder();
    write(node, out, "");
    return out.append('\n').toString();
  }

  private static void write(Object node, StringBuilder out, String indent) {
    if (node == null) {
      out.append("null");
    } else if (node instanceof Map<?, ?> map) {
      if (map.isEmpty()) {
        out.append("{}");
        return;
      }
      String inner = indent + "  ";
      out.append("{\n");
      int i = 0;
      for (Map.Entry<?, ?> field : map.entrySet()) {
        out.append(inner).append(Json.encode(String.valueOf(field.getKey()))).append(": ");
        write(field.getValue(), out, inner);
        out.append(++i < map.size() ? ",\n" : "\n");
      }
      out.append(indent).append('}');
    } else if (node instanceof List<?> list) {
      if (list.isEmpty()) {
        out.append("[]");
        return;
      }
      String inner = indent + "  ";
      out.append("[\n");
      for (int i = 0; i < list.size(); i++) {
        out.append(inner);
        write(list.get(i), out, inner);
        out.append(i + 1 < list.size() ? ",\n" : "\n");
      }
      out.append(indent).append(']');
    } else if (node instanceof String text) {
      out.append(Json.encode(text));
    } else if (node instanceof Number || node instanceof Boolean) {
      out.append(node);
    } else {
      throw new IllegalArgumentException("not a JSON value: " + node.getClass());
    }
  }

  private static String segment(String key) {
    if (PLAIN_KEY.matcher(key).matches()) {
      return "." + key;
    }
    return "['" + key.replace("\\", "\\\\").replace("'", "\\'") + "']";
  }
}
