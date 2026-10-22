package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.core.support.json.JsonValue;
import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import eu.wohlben.qits.workspacesrunner.RunnerProviderStates.Frame;
import eu.wohlben.qits.workspacesrunner.contracts.ClasspathPactLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Verifies qits-workspaces-service's message pact against the real runner</b> (qits-950, A2):
 * every interaction of {@code pacts/qits-workspaces-service_qits-workspaces-runner-daemon.json},
 * off the pinned {@code
 * eu.wohlben.qits:qits-workspaces-service-pacts-qits-workspaces-runner-daemon} ({@link
 * ClasspathPactLoader}), through pact-jvm's {@link MessageTestTarget}.
 *
 * <p><b>Each frame is the runner's, made on the wire in the state the pact names</b> — never a
 * recorded file and never JSON built here. A {@code @State} method notes which of {@link
 * RunnerProviderStates}' states the interaction is in; the {@code @PactVerifyProvider} for its
 * description then runs {@link RunnerProviderStates#produce}: a fresh node and host, the real
 * {@link WorkspacesRunnerDomain} under qits-runner-toolkit's real {@code RunnerRuntime}, the
 * trigger pulled, and the frame answered as {@link WireTap} saw the runner write it — the path
 * {@code RunnerGoldenMasterRecordingTest} records through, so what is verified is what was recorded
 * from. pact-jvm then matches it against the interaction's rules.
 *
 * <p><b>It refuses three things before a frame is made</b> ({@link #target}): a provider state the
 * runner does not answer for, an interaction without {@code comments.references.qits-call} and
 * {@code qits-trigger}, and a {@code qits-trigger} or {@code qits-call} that is not what the runner
 * records for that state and frame — a pact citing a cause the runner does not act on would verify
 * a frame for the wrong reason. A description no {@code @PactVerifyProvider} answers is pact-jvm's
 * own failure.
 *
 * <p>One {@code @PactVerifyProvider} per description, as pact-jvm keys them; a description shared
 * by several states ({@code ack: frame:inventory} is recorded in three) is told apart by the state.
 */
@EnabledOnOs(OS.LINUX)
@Provider(RunnerFramesProviderPactTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
class RunnerFramesProviderPactTest {

  /** This runner, as a consumer's pact names it: the repository. */
  static final String PROVIDER = "qits-workspaces-runner-daemon";

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  /** The states the runner answers for: every state a recorded frame is made in. */
  static final Set<String> STATES = new LinkedHashSet<>();

  static {
    RunnerProviderStates.FRAMES.forEach(f -> STATES.add(f.state()));
  }

  @TempDir Path work;

  private Interaction interaction;
  private String state;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    String consumer = pact.getConsumer().getName();
    for (ProviderState given : interaction.getProviderStates()) {
      if (!STATES.contains(given.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + given.getName()
                + "', which the runner does not answer for — it answers for "
                + STATES);
      }
    }
    JsonValue references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    this.interaction = interaction;
    context.setTarget(new MessageTestTarget(List.of(getClass().getPackageName())));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void theRunnerSendsTheFrameThePactSays(PactVerificationContext context) {
    context.verifyInteraction();
  }

  // --- the states
  // ---------------------------------------------------------------------------------

  @State(RunnerProviderStates.NO_WORKSPACES)
  void noWorkspaces() {
    state = RunnerProviderStates.NO_WORKSPACES;
  }

  @State(RunnerProviderStates.RECONNECTING)
  void reconnecting() {
    state = RunnerProviderStates.RECONNECTING;
  }

  @State(RunnerProviderStates.RUNNING)
  void running() {
    state = RunnerProviderStates.RUNNING;
  }

  @State(RunnerProviderStates.STOPPED)
  void stopped() {
    state = RunnerProviderStates.STOPPED;
  }

  @State(RunnerProviderStates.FREE_SLOT)
  void freeSlot() {
    state = RunnerProviderStates.FREE_SLOT;
  }

  @State(RunnerProviderStates.TAKE)
  void take() {
    state = RunnerProviderStates.TAKE;
  }

  @State(RunnerProviderStates.TAKE_FAILS)
  void takeFails() {
    state = RunnerProviderStates.TAKE_FAILS;
  }

  @State(RunnerProviderStates.EXITS)
  void exits() {
    state = RunnerProviderStates.EXITS;
  }

  @State(RunnerProviderStates.HEALTHY)
  void healthy() {
    state = RunnerProviderStates.HEALTHY;
  }

  @State(RunnerProviderStates.LOGGED_OUT)
  void loggedOut() {
    state = RunnerProviderStates.LOGGED_OUT;
  }

  @State(RunnerProviderStates.NO_ESTATE)
  void noEstate() {
    state = RunnerProviderStates.NO_ESTATE;
  }

  // --- the frames, one per description ----------------------------------------------------------

  @PactVerifyProvider("connect: frame:hello")
  MessageAndMetadata hello() throws Exception {
    return produce();
  }

  @PactVerifyProvider("reconnect: frame:hello")
  MessageAndMetadata helloOnAReconnect() throws Exception {
    return produce();
  }

  @PactVerifyProvider("heartbeat: frame:heartbeat")
  MessageAndMetadata heartbeat() throws Exception {
    return produce();
  }

  @PactVerifyProvider("ack: frame:inventory")
  MessageAndMetadata inventory() throws Exception {
    return produce();
  }

  @PactVerifyProvider("backlog: frame:reserve")
  MessageAndMetadata reserve() throws Exception {
    return produce();
  }

  @PactVerifyProvider("take: frame:launched")
  MessageAndMetadata launched() throws Exception {
    return produce();
  }

  @PactVerifyProvider("take: frame:launchFailed")
  MessageAndMetadata launchFailed() throws Exception {
    return produce();
  }

  @PactVerifyProvider("exit-watch: frame:exited")
  MessageAndMetadata exited() throws Exception {
    return produce();
  }

  @PactVerifyProvider("stop: frame:stopped")
  MessageAndMetadata stoppedFrame() throws Exception {
    return produce();
  }

  @PactVerifyProvider("delete: frame:deleted")
  MessageAndMetadata deleted() throws Exception {
    return produce();
  }

  @PactVerifyProvider("healthCheck: frame:healthChecked")
  MessageAndMetadata healthChecked() throws Exception {
    return produce();
  }

  @PactVerifyProvider("estate: frame:loginState")
  MessageAndMetadata loginStateAfterTheEstate() throws Exception {
    return produce();
  }

  @PactVerifyProvider("probeLogin: frame:loginState")
  MessageAndMetadata loginStateWhenProbed() throws Exception {
    return produce();
  }

  /**
   * The interaction's frame, made by the runner in the noted state: the {@link
   * RunnerProviderStates#FRAMES} entry for (state, the {@code qits-call} frame), refused unless its
   * trigger is the interaction's {@code qits-trigger}.
   */
  private MessageAndMetadata produce() throws Exception {
    if (state == null) {
      fail(interaction.getDescription() + ": no @State ran, so there is no state to make it in");
    }
    var references = interaction.getComments().get("references").asObject();
    var call = references.get("qits-call").asObject();
    var trigger = references.get("qits-trigger").asObject();
    String type = text(call, "frame");
    if (!PROVIDER.equals(text(call, "app"))) {
      fail(interaction.getDescription() + ": qits-call names " + call + ", not " + PROVIDER);
    }
    Frame frame =
        RunnerProviderStates.FRAMES.stream()
            .filter(f -> f.state().equals(state) && f.type().equals(type))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        interaction.getDescription()
                            + ": the runner makes no "
                            + type
                            + " in '"
                            + state
                            + "'"));
    String kind = text(trigger, "kind");
    Map<String, String> recorded =
        Map.of("kind", frame.trigger().kind(), "app", PROVIDER, kind, frame.trigger().name());
    if (!kind.equals(frame.trigger().kind())
        || !frame.trigger().name().equals(text(trigger, kind))
        || !PROVIDER.equals(text(trigger, "app"))) {
      fail(
          interaction.getDescription()
              + " ["
              + state
              + "]: qits-trigger "
              + trigger
              + ", and the runner sends it on "
              + recorded);
    }
    String sent =
        RunnerProviderStates.produce(frame, Files.createDirectories(work.resolve("node")));
    return new MessageAndMetadata(
        sent.getBytes(StandardCharsets.UTF_8), Map.of("contentType", "application/json"));
  }

  private static String text(JsonValue.Object group, String key) {
    JsonValue value = group.get(key);
    return value == null || !value.isString() ? "" : value.asString();
  }
}
