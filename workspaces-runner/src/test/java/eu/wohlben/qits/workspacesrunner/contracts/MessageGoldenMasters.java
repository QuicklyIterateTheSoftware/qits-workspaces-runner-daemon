package eu.wohlben.qits.workspacesrunner.contracts;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <b>The frames one message provider records, read off the test classpath</b> (qits-950): the
 * {@code frame:<type>} entries of its {@code golden-masters/index.json} and the frame each names.
 * The message counterpart of qits-pact-consumer's {@code GoldenMasters}, which reads HTTP answers
 * and has no notion of a frame or of what made it be sent.
 *
 * <p><b>Every golden-masters jar puts its tree at the same {@code golden-masters/}</b>, so the
 * index is chosen by its {@code provider} field (the application name) and its files are read
 * beside it — qits-idp's, qits-observability's and qits-workspaces' trees share this classpath.
 *
 * <p>Plain Vert.x JSON, no Jackson: the runner's suite takes none on, and the toolkit's contracts
 * module, where a second runner would reach this class, has Vert.x already.
 */
public final class MessageGoldenMasters {

  /** Where every golden-masters jar puts its tree. */
  public static final String ROOT = "golden-masters/";

  /** The operationId of a recorded frame: {@code frame:<type>}. */
  public static final String FRAME_PREFIX = "frame:";

  /**
   * A provider as a pact names it (its repository) and as its golden-master index does (its
   * application).
   */
  public record Provider(String repository, String application) {
    public Provider {
      Objects.requireNonNull(repository, "repository");
      Objects.requireNonNull(application, "application");
    }
  }

  /**
   * What made the provider send a frame — the {@code qits-trigger} reference, the estate's four-key
   * group {@code {kind, app, <key>: value}}: {@code app} is the provider's repository, because the
   * cause sits on the sending side, and {@code key} is the one the kind uses ({@link
   * #KEY_OF_KIND}): an {@code operation} names its {@code operationId}, an {@code event}, a {@code
   * schedule} and a {@code frame} name themselves.
   */
  public record Trigger(String kind, String app, String key, String value) {

    /** The key each trigger kind names its value by, as the platform's readers expect it. */
    public static final Map<String, String> KEY_OF_KIND =
        Map.of(
            "operation", "operationId",
            "event", "event",
            "schedule", "schedule",
            "frame", "frame",
            "ui", "interaction");

    public Trigger {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(app, "app");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
    }

    /** A cause on {@code provider}'s side, keyed as its kind is. */
    public static Trigger sent(Provider provider, String kind, String value) {
      String key = KEY_OF_KIND.get(kind);
      if (key == null) {
        throw new IllegalArgumentException(
            "no trigger kind '" + kind + "': " + KEY_OF_KIND.keySet());
      }
      return new Trigger(kind, provider.repository(), key, value);
    }

    /** The {@code qits-trigger} group, every value a string, in a fixed key order. */
    public Map<String, String> reference() {
      Map<String, String> ref = new LinkedHashMap<>();
      ref.put("kind", kind);
      ref.put("app", app);
      ref.put(key, value);
      return ref;
    }
  }

  /**
   * One recorded frame, as the index describes it.
   *
   * @param params the state's frozen example params
   * @param frame the wire {@code type}
   * @param ids the JSONPaths ({@code $.a.b}, elements as {@code [*]}) whose value is a frozen UUID
   * @param instants the paths holding a frozen ISO-8601 instant
   * @param strings the paths holding a string with a frozen value in it
   * @param numbers the paths holding a frozen number — a row id, which the provider's own state
   *     chose
   */
  public record Recorded(
      Provider provider,
      String state,
      Map<String, String> params,
      String operationId,
      String frame,
      Trigger trigger,
      String file,
      Set<String> ids,
      Set<String> instants,
      Set<String> strings,
      Set<String> numbers) {

    /**
     * The paths whose recorded value the state chose and a consumer may lay its own over: ids,
     * strings and numbers. An instant is matched by shape alone, so it needs no swap.
     */
    public Set<String> substitutable() {
      Set<String> out = new LinkedHashSet<>(ids);
      out.addAll(strings);
      out.addAll(numbers);
      return Set.copyOf(out);
    }
  }

  private record Index(JsonObject root, URL location) {}

  private final Provider provider;
  private volatile Index index;

  private MessageGoldenMasters(Provider provider) {
    this.provider = provider;
  }

  /** {@code provider}'s recorded frames; the jar is looked up on first use. */
  public static MessageGoldenMasters of(Provider provider) {
    return new MessageGoldenMasters(provider);
  }

  public Provider provider() {
    return provider;
  }

  /** Every {@code frame:<type>} entry, in index order (states by slug, operations by id). */
  public List<Recorded> frames() {
    List<Recorded> out = new ArrayList<>();
    for (Object state : index().root().getJsonArray("states", new JsonArray())) {
      JsonObject s = (JsonObject) state;
      for (Object operation : s.getJsonArray("operations", new JsonArray())) {
        JsonObject op = (JsonObject) operation;
        if (op.getString("operationId", "").startsWith(FRAME_PREFIX)) {
          out.add(recorded(s, op));
        }
      }
    }
    return out;
  }

  /** The recording of {@code frame} in {@code state}; fails naming both when there is none. */
  public Recorded frame(String state, String frame) {
    return frames().stream()
        .filter(r -> r.state().equals(state) && r.frame().equals(frame))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    provider.application()
                        + "'s golden masters record no "
                        + FRAME_PREFIX
                        + frame
                        + " in state '"
                        + state
                        + "'"));
  }

  /** Whether {@code state} is a state the provider records at all. */
  public boolean hasState(String state) {
    return frames().stream().anyMatch(r -> r.state().equals(state));
  }

  /** The recorded frame, byte for byte as the jar carries it. */
  @SuppressWarnings("deprecation") // new URL(context, spec) is the one resolver jar: URLs have
  public String text(Recorded recorded) {
    try {
      return read(new URL(index().location(), recorded.file()));
    } catch (MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** {@link #text}, parsed: a fresh tree each call. */
  public JsonObject json(Recorded recorded) {
    return new JsonObject(text(recorded));
  }

  private Recorded recorded(JsonObject state, JsonObject op) {
    String name = state.getString("name");
    String operationId = op.getString("operationId");
    String frame = op.getString("frame", "");
    if (!operationId.equals(FRAME_PREFIX + frame)) {
      throw new IllegalStateException(
          "golden master "
              + name
              + "/"
              + operationId
              + " is no frame entry (frame: '"
              + frame
              + "')");
    }
    Map<String, String> params = new LinkedHashMap<>();
    state
        .getJsonObject("params", new JsonObject())
        .forEach(e -> params.put(e.getKey(), String.valueOf(e.getValue())));
    JsonObject trigger = op.getJsonObject("trigger", new JsonObject());
    String kind = trigger.getString("kind", "");
    String key = Trigger.KEY_OF_KIND.get(kind);
    Object value = key == null ? null : trigger.getValue(key);
    if (value == null || value.toString().isBlank()) {
      throw new IllegalStateException(
          provider.application()
              + "'s golden master "
              + name
              + "/"
              + operationId
              + " records no trigger {kind, <key of the kind>}: "
              + trigger);
    }
    String app = trigger.getString("app", provider.repository());
    if (!app.equals(provider.repository())) {
      throw new IllegalStateException(
          provider.application()
              + "'s golden master "
              + name
              + "/"
              + operationId
              + " records a trigger of "
              + app
              + ", not of the provider "
              + provider.repository());
    }
    JsonObject frozen = op.getJsonObject("frozen", new JsonObject());
    return new Recorded(
        provider,
        name,
        Map.copyOf(params),
        operationId,
        frame,
        new Trigger(kind, app, key, value.toString()),
        op.getString("file"),
        strings(frozen.getJsonArray("ids")),
        strings(frozen.getJsonArray("instants")),
        strings(frozen.getJsonArray("strings")),
        strings(frozen.getJsonArray("numbers")));
  }

  private static Set<String> strings(JsonArray array) {
    Set<String> out = new LinkedHashSet<>();
    if (array != null) {
      array.forEach(e -> out.add(String.valueOf(e)));
    }
    return Set.copyOf(out);
  }

  private Index index() {
    Index found = index;
    if (found == null) {
      found = load();
      index = found;
    }
    return found;
  }

  private Index load() {
    List<String> seen = new ArrayList<>();
    for (URL url : resources(ROOT + "index.json")) {
      JsonObject loaded = new JsonObject(read(url));
      String owner = loaded.getString("provider", "");
      seen.add(owner);
      if (!provider.application().equals(owner)) {
        continue;
      }
      if (loaded.getInteger("formatVersion", 0) != 1) {
        throw new IllegalStateException(
            owner
                + "'s golden-masters/index.json is formatVersion "
                + loaded.getValue("formatVersion")
                + "; this reader reads formatVersion 1");
      }
      return new Index(loaded, url);
    }
    throw new IllegalStateException(
        "no golden-masters/index.json of "
            + provider.application()
            + " on the test classpath (found "
            + seen
            + ") — is eu.wohlben.qits:"
            + provider.application()
            + "-golden-masters a test dependency of this module?");
  }

  private static List<URL> resources(String name) {
    ClassLoader loader =
        Objects.requireNonNullElse(
            MessageGoldenMasters.class.getClassLoader(), ClassLoader.getSystemClassLoader());
    List<URL> out = new ArrayList<>();
    try {
      Enumeration<URL> found = loader.getResources(name);
      while (found.hasMoreElements()) {
        out.add(found.nextElement());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }

  private static String read(URL url) {
    try (InputStream in = url.openStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + url, e);
    }
  }
}
