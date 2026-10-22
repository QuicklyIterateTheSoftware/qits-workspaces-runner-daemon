package eu.wohlben.qits.workspacesrunner.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.support.Json;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenFiles;
import eu.wohlben.qits.workspacesrunner.contracts.MessageGoldenMasters.Recorded;
import eu.wohlben.qits.workspacesrunner.contracts.MessageGoldenMasters.Trigger;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <b>Every frame one provider sends a consumer, as a consumer message pact</b> (qits-950): the
 * rows, the V4 {@code Asynchronous/Messages} pact they make from the provider's pinned golden
 * masters, each row's frame handed to the consumer's real handling, and the committed {@code
 * pacts/<consumer>_<provider>.json}. qits-workspaces-service's {@code ContractTable} with only its
 * message half, written for a runner: plain JUnit, Vert.x JSON, and nothing of the workspaces
 * runner in it, so qits-runner-javalib's {@code runner-toolkit-contracts} can take it for the next
 * runner that pacts its host's frames.
 *
 * <p><b>Each interaction carries {@code comments.references}</b>: {@code qits-call} {@code {app,
 * frame}} — the provider and the frame it sends — and {@code qits-trigger} {@code {kind, app,
 * <key>}} (the key its kind uses: {@code operationId}, {@code event}, …), what on the provider's
 * side made it send the frame, as its index records it. A row that names another cause than the
 * index fails the pact's build: a false {@code qits-trigger} is what the reference exists to
 * prevent.
 *
 * <p><b>The committed file is pact-jvm's own writing, normalised</b> by qits-pact-consumer's {@link
 * ConsumerPact#normalise} (interactions sorted, pact-jvm's version stripped, two-space indent), and
 * compared by default; {@code -Dgolden.update=true} rewrites it ({@link GoldenFiles}).
 */
public record MessageContract(String consumer, MessageGoldenMasters masters, List<Row> rows) {

  /** The consumer handling one frame, and asserting what it did. */
  @FunctionalInterface
  public interface Receive {
    void run(PactMessage message, Path workDir) throws Exception;
  }

  /**
   * One (trigger, frame) the provider sends in {@code state}, recorded as {@code frame:<frame>},
   * and what the consumer does with it.
   */
  public record Row(Trigger trigger, String state, String frame, Receive receive) {
    public Row {
      Objects.requireNonNull(trigger, "trigger: every row names the cause of its frame");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(frame, "frame");
      Objects.requireNonNull(receive, "receive");
    }

    public String operationId() {
      return MessageGoldenMasters.FRAME_PREFIX + frame;
    }

    /** The trigger first, so (description, state) stays unique: {@code <cause>: frame:<type>}. */
    public String description() {
      return trigger.value() + ": " + operationId();
    }
  }

  public MessageContract {
    Objects.requireNonNull(consumer, "consumer");
    Objects.requireNonNull(masters, "masters");
    rows = List.copyOf(rows);
    Set<String> unique = new HashSet<>();
    for (Row row : rows) {
      if (!unique.add(row.description() + "\u0000" + row.state())) {
        throw new IllegalArgumentException(
            "two rows share description and state: "
                + row.description()
                + " ["
                + row.state()
                + "]");
      }
    }
  }

  /** {@code <consumer>_<provider repository>.json}. */
  public String file() {
    return consumer + "_" + masters.provider().repository() + ".json";
  }

  /** The pact of every row. */
  public V4Pact pact() {
    return pact(rows);
  }

  /** A pact holding only {@code selected}. */
  public V4Pact pact(List<Row> selected) {
    PactBuilder builder =
        new PactBuilder(consumer, masters.provider().repository(), PactSpecVersion.V4);
    for (Row row : selected) {
      add(builder, row);
    }
    return builder.toPact();
  }

  /** The recording a row is built from. */
  public Recorded recorded(Row row) {
    return masters.frame(row.state(), row.frame());
  }

  private void add(PactBuilder builder, Row row) {
    Recorded recorded = recorded(row);
    if (!recorded.trigger().equals(row.trigger())) {
      throw new IllegalStateException(
          masters.provider().application()
              + " records "
              + row.state()
              + "/"
              + row.operationId()
              + " as sent on "
              + recorded.trigger().reference()
              + ", and the row names "
              + row.trigger().reference());
    }
    var body = MessageBodies.body(recorded, masters.json(recorded));
    Map<String, Object> references = new LinkedHashMap<>();
    Map<String, String> call = new LinkedHashMap<>();
    call.put("app", masters.provider().repository());
    call.put("frame", row.frame());
    references.put("qits-call", call);
    references.put("qits-trigger", row.trigger().reference());
    builder.expectsToReceiveMessageInteraction(
        row.description(),
        message -> {
          message.state(row.state(), new LinkedHashMap<String, Object>(recorded.params()));
          message.withContents(contents -> contents.withContent(body));
          // pact-jvm's DSL has no setter for an arbitrary comment group, but the V4 model's
          // comments map is mutable and written verbatim.
          message.getInteraction().getComments().put("references", Json.toJson(references));
          return message;
        });
  }

  /** The row {@code description} names in {@code state}; fails naming both when none does. */
  public Row row(String description, String state) {
    return rows.stream()
        .filter(r -> r.description().equals(description) && r.state().equals(state))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    masters.provider().repository()
                        + ": no row "
                        + description
                        + " ["
                        + state
                        + "]"));
  }

  /**
   * A row's {@code receive}, handed the pact's message for it — the interaction exactly as {@link
   * #pact(List)} writes it — with the state's params and the paths the provider froze.
   */
  public void receive(Row row, Path workDir) throws Exception {
    V4Pact single = pact(List.of(row));
    var message = (V4Interaction.AsynchronousMessage) single.getInteractions().get(0);
    Recorded recorded = recorded(row);
    row.receive()
        .run(new PactMessage(message, recorded.params(), recorded.substitutable()), workDir);
  }

  /** The pact as pact-jvm's own writer serialises it. */
  public String written() {
    StringWriter out = new StringWriter();
    try (PrintWriter writer = new PrintWriter(out)) {
      DefaultPactWriter.INSTANCE.writePact(pact(), writer, PactSpecVersion.V4);
    }
    return out.toString();
  }

  /**
   * Leaves the raw pact in {@code target/pacts/<file>}, and compares its normalised form with the
   * committed {@code pacts/<file>} — or rewrites that under {@code -Dgolden.update=true}.
   */
  public void compareOrWritePactFile() {
    try {
      String raw = written();
      Path scratch = Path.of("target", "pacts", file());
      Files.createDirectories(scratch.getParent());
      Files.writeString(scratch, raw);
      GoldenFiles.compareOrWrite(
          ConsumerPact.pactsDirectory().resolve(file()), ConsumerPact.normalise(raw));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Both references on every interaction of the written pact, as the platform reads them: {@code
   * qits-call} {@code {app, frame}} naming the provider and a frame, {@code qits-trigger} naming
   * its kind, the provider as app and the kind's own value, every value a non-blank string; one
   * interaction per row, each a V4 message.
   */
  public void assertEveryInteractionCarriesBothReferences() {
    JsonObject pact;
    try {
      pact = new JsonObject(ConsumerPact.normalise(written()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    String provider = masters.provider().repository();
    assertEquals(consumer, pact.getJsonObject("consumer").getString("name"));
    assertEquals(provider, pact.getJsonObject("provider").getString("name"));
    assertEquals(
        "4.0",
        pact.getJsonObject("metadata").getJsonObject("pactSpecification").getString("version"));
    JsonArray interactions = pact.getJsonArray("interactions");
    assertEquals(rows.size(), interactions.size(), "one interaction per row");
    for (Object each : interactions) {
      JsonObject interaction = (JsonObject) each;
      String description = interaction.getString("description");
      assertEquals("Asynchronous/Messages", interaction.getString("type"), description);
      JsonObject references =
          interaction.getJsonObject("comments", new JsonObject()).getJsonObject("references");
      assertTrue(references != null, description + ": no comments.references");
      JsonObject call = references.getJsonObject("qits-call");
      assertStrings(description, "qits-call", call, 2);
      assertEquals(provider, call.getString("app"), description);
      assertTrue(call.containsKey("frame"), description + ": qits-call.frame");
      JsonObject trigger = references.getJsonObject("qits-trigger");
      assertStrings(description, "qits-trigger", trigger, 3);
      String key = Trigger.KEY_OF_KIND.get(trigger.getString("kind"));
      assertTrue(key != null, description + ": unknown trigger kind " + trigger.encode());
      assertTrue(trigger.containsKey(key), description + ": qits-trigger." + key);
      assertEquals(
          provider, trigger.getString("app"), description + ": the cause is the provider's");
    }
  }

  private static void assertStrings(String description, String name, JsonObject group, int size) {
    assertTrue(group != null, description + ": no " + name);
    assertEquals(size, group.size(), description + ": " + name + " " + group.encode());
    group.forEach(
        e ->
            assertTrue(
                e.getValue() instanceof String s && !s.isBlank(),
                description + ": " + name + "." + e.getKey() + " must be a non-blank string"));
  }
}
