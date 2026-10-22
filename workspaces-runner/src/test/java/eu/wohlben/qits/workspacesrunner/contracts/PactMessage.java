package eu.wohlben.qits.workspacesrunner.contracts;

import au.com.dius.pact.core.matchers.BodyMatchResult;
import au.com.dius.pact.core.matchers.JsonContentMatcher;
import au.com.dius.pact.core.matchers.MatchingContext;
import au.com.dius.pact.core.model.ContentType;
import au.com.dius.pact.core.model.OptionalBody;
import au.com.dius.pact.core.model.V4Interaction;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * <b>One message interaction of a consumer pact, as the consumer is handed it</b> (qits-950): the
 * frame the provider sends, ready to be fed to the consumer's real handling — qits-workspaces-
 * service's class of the same name, for the runner's side of the socket.
 *
 * <p><b>The text is the pact's own example with the state's frozen values swapped for the
 * consumer's</b>: the service recorded {@code workspaceId} 1, and the runner's test holds a
 * workspace of its own. {@link #text(Map)} replaces a leaf holding a state param's frozen example —
 * as a number or as a string, a frame spells a row id both ways — <b>but only at a path the
 * provider froze</b> ({@link MessageGoldenMasters.Recorded#substitutable}). The service's helper
 * swaps every equal leaf, which would turn an {@code ack}'s {@code slots: 1} and its {@code
 * capabilityVersion: 1} into row ids along with its {@code adoptedWorkspaces}; the frozen paths are
 * exactly the values the state chose. {@link #text(Map, Consumer)} edits a field no param names (a
 * {@code healthCheck}'s request id, which the service freezes without a param).
 *
 * <p><b>Every text handed out is first matched against the interaction's own rules</b> by
 * pact-jvm's JSON matcher, exactly as a provider's message is at verification: a substitution the
 * contract does not admit fails here, so what a consumer test feeds is always a message the pact
 * allows.
 */
public final class PactMessage {

  private final V4Interaction.AsynchronousMessage interaction;
  private final Map<String, String> params;
  private final Set<String> substitutable;

  public PactMessage(
      V4Interaction.AsynchronousMessage interaction,
      Map<String, String> params,
      Set<String> substitutable) {
    this.interaction = Objects.requireNonNull(interaction, "interaction");
    this.params = Map.copyOf(params);
    this.substitutable = Set.copyOf(substitutable);
  }

  public String description() {
    return interaction.getDescription();
  }

  /** The provider state's frozen example params. */
  public Map<String, String> params() {
    return params;
  }

  /** The pact's example of the frame, a fresh tree each call. */
  public JsonObject example() {
    return new JsonObject(interaction.contentsAsString());
  }

  /** The example as it stands, matched. */
  public String text() {
    return text(Map.of());
  }

  /** The example with each named state param's frozen value replaced by {@code values}, matched. */
  public String text(Map<String, ?> values) {
    return text(values, frame -> {});
  }

  /** As {@link #text(Map)}, then {@code edit} applied to the tree, matched. */
  public String text(Map<String, ?> values, Consumer<JsonObject> edit) {
    Map<String, String> swaps = new LinkedHashMap<>();
    values.forEach(
        (name, value) -> {
          String frozen = params.get(name);
          if (frozen == null) {
            throw new IllegalArgumentException(
                description()
                    + ": the state names no param '"
                    + name
                    + "' (it has "
                    + params
                    + ")");
          }
          swaps.put(frozen, String.valueOf(value));
        });
    JsonObject frame = example();
    Map<String, Integer> swapped = new LinkedHashMap<>();
    swapObject(frame, "$", swaps, swapped);
    for (String frozen : swaps.keySet()) {
      if (!swapped.containsKey(frozen)) {
        throw new IllegalArgumentException(
            description()
                + ": no frozen path "
                + substitutable
                + " holds the param value '"
                + frozen
                + "' to swap");
      }
    }
    edit.accept(frame);
    String text = frame.encode();
    assertAdmitted(text);
    return text;
  }

  /** Fails, naming each mismatch, unless the interaction's rules admit {@code text}. */
  public void assertAdmitted(String text) {
    BodyMatchResult result =
        JsonContentMatcher.INSTANCE.matchBody(
            interaction.getContents().getContents(),
            OptionalBody.body(
                text.getBytes(StandardCharsets.UTF_8), ContentType.Companion.getJSON()),
            new MatchingContext(
                interaction.getContents().getMatchingRules().rulesForCategory("body"),
                true,
                Map.of(),
                false));
    if (!result.matchedOk()) {
      throw new AssertionError(
          description()
              + ": the frame fed to the runner is not one the pact admits — "
              + result.getMismatches()
              + "\n  "
              + text);
    }
  }

  private void swapObject(
      JsonObject object, String path, Map<String, String> swaps, Map<String, Integer> swapped) {
    for (String key : object.fieldNames()) {
      Object value = object.getValue(key);
      object.put(key, swap(value, path + "." + key, swaps, swapped));
    }
  }

  private Object swap(
      Object node, String path, Map<String, String> swaps, Map<String, Integer> swapped) {
    if (node instanceof JsonObject object) {
      swapObject(object, path, swaps, swapped);
      return object;
    }
    if (node instanceof JsonArray array) {
      for (int i = 0; i < array.size(); i++) {
        array.set(i, swap(array.getValue(i), path + "[*]", swaps, swapped));
      }
      return array;
    }
    if (!substitutable.contains(path) || node == null) {
      return node;
    }
    boolean integral = node instanceof Integer || node instanceof Long;
    if (!(node instanceof String) && !integral) {
      return node;
    }
    String replacement = swaps.get(node.toString());
    if (replacement == null) {
      return node;
    }
    swapped.merge(node.toString(), 1, Integer::sum);
    return integral ? (Object) Long.parseLong(replacement) : replacement;
  }
}
