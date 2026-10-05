package eu.wohlben.qits.workspacesrunner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerDecodeException;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The workspace frames' contract, framework-free: every frame survives {@code encode → decode}
 * through the base codec and this delegate unchanged; each frame's JSON spelling is pinned, key
 * order included; unknown fields are ignored and an unknown type is the base's typed error; and the
 * lists that are desired or observed state refuse to be read as empty when they are absent.
 *
 * <p>The lifecycle frames' byte identity with CI is qits-runner-protocol's {@code
 * CiWireCompatibilityTest} and is not repeated here.
 */
class WorkspacesRunnerCodecTest {

  private static final RunnerCodec<WorkspacesRunnerMessage> CODEC =
      new RunnerCodec<>(WorkspacesRunnerProtocol.VOCABULARY, new WorkspacesRunnerCodec());

  private static final String IMAGE = "registry.qits.example.eu/qits/workspace:2026.1004.1";

  private static WorkspaceSpec fullSpec() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_WORKSPACE_DAEMON_WORKSPACE_ID", "main");
    env.put("TZ", "Europe/Berlin");
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("qits.repository", "qits-qits");
    labels.put("qits.workspace", "main");
    return new WorkspaceSpec(
        IMAGE,
        env,
        List.of(
            new Mount(Mount.Volume.WORKSPACE, "/workspace"),
            new Mount(Mount.Volume.DOT_CLAUDE, "/claude-home"),
            new Mount(Mount.Volume.M2, "/caches/m2"),
            new Mount(Mount.Volume.PNPM, "/caches/pnpm")),
        labels,
        "4g",
        "8g",
        4096L,
        "2",
        600,
        true);
  }

  private static WorkspaceSpec bareSpec() {
    return new WorkspaceSpec(IMAGE, Map.of(), List.of(), Map.of(), null, null, null, null, null, false);
  }

  private static HealthChecked fullHealthChecked() {
    Map<String, Object> container = new LinkedHashMap<>();
    container.put("name", "qits-workspaces-runner-ws-2201-1a2b3c4d");
    container.put("rowId", 2201L);
    container.put("finishedAt", null);
    Map<String, Object> inventory = new LinkedHashMap<>();
    inventory.put("containers", List.of(container));
    inventory.put("runnerContainer", null);
    return new HealthChecked(
        false,
        "session: timed out after 1s",
        "req-1",
        List.of(
            new CheckResult("docker", true, "docker 27.3.1", Map.of("serverVersion", "27.3.1")),
            new CheckResult("nodeInventory", true, "1 container, 0 volumes", inventory),
            new CheckResult("session", false, "timed out after 1s", Map.of())));
  }

  /** One of every frame, and a second shape where a field is nullable or a list can be empty. */
  private static List<WorkspacesRunnerMessage> everyFrame() {
    return List.of(
        new Estate(List.of(2201L, 7L), IMAGE),
        new Estate(List.of(), IMAGE),
        new Take(2201L, fullSpec()),
        new Take(7L, bareSpec()),
        new Stop(2201L),
        new Delete(2201L),
        new ProbeLogin(),
        new HealthCheck(),
        new HealthCheck("req-1"),
        new Inventory(
            List.of(new HeldContainer(2201L, true, "sha256:abc"), new HeldContainer(7L, false, null)),
            List.of(2201L, 7L),
            "qits-workspaces-runner-dot-claude-1a2b3c4d"),
        new Inventory(List.of(), List.of(), null),
        new Launched(2201L, "4f6c1e0b2a9d"),
        new LaunchFailed(2201L, "docker: Error response from daemon: no space left on device"),
        new Stopped(2201L),
        new Deleted(2201L),
        new Exited(2201L, 137),
        new LoginState(LoginPresence.PRESENT, LoginPresence.ABSENT, "2026-10-05T04:38:19Z"),
        new LoginState(LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, null),
        new HealthChecked(true, "ran " + IMAGE + " and saw it running"),
        new HealthChecked(false, "it never ran"),
        fullHealthChecked());
  }

  @Test
  void everyFrameRoundTripsThroughTheBaseCodecAndTheDelegate() {
    for (WorkspacesRunnerMessage frame : everyFrame()) {
      RunnerMessage back = CODEC.decode(CODEC.encode(frame));
      assertEquals(frame, back, () -> "round trip of " + frame);
    }
  }

  @Test
  void theRoundTripCoversEveryPermittedFrame() {
    Set<String> permitted =
        Arrays.stream(WorkspacesRunnerMessage.class.getPermittedSubclasses())
            .map(Class::getSimpleName)
            .collect(Collectors.toSet());
    Set<String> covered =
        everyFrame().stream().map(f -> f.getClass().getSimpleName()).collect(Collectors.toSet());
    assertEquals(permitted, covered, "a frame was added without a round trip");
    assertEquals(
        WorkspacesRunnerProtocol.Type.ALL,
        everyFrame().stream()
            .map(f -> (String) CODEC.encode(f).get("type"))
            .collect(Collectors.toSet()),
        "every wire type is spoken by a frame, and no frame by an undeclared type");
  }

  @Test
  void theBaseCodecAcceptsTheDelegateNoWorkspaceTypeIsALifecycleType() {
    // RunnerCodec refuses a domain type that is a lifecycle type at construction; that this one
    // builds is the assertion. A lifecycle frame still goes through the base, carrying the
    // workspaces vocabulary.
    Hello hello =
        new Hello(
            WorkspacesRunnerBinary.VERSION,
            WorkspacesRunnerProtocol.CAPABILITY_VERSION,
            2,
            new RunnerCapabilities(true, "amd64", "linux", Map.of()),
            List.of("2201"),
            Map.of());
    Map<String, Object> wire = CODEC.encode(hello);
    assertEquals(List.of("2201"), wire.get("heldWorkspaces"));
    assertEquals(hello, CODEC.decode(wire));
    Map<String, Object> ack = new LinkedHashMap<>();
    ack.put("type", "ack");
    ack.put("capabilityVersion", 1);
    ack.put("slots", 2);
    ack.put("adoptedWorkspaces", List.of("2201"));
    assertEquals(List.of("2201"), ((Ack) CODEC.decode(ack)).adopted());
  }

  // --- the golden spelling ---------------------------------------------------------------------

  @Test
  void eachFrameIsSpelledExactlyAsTheFeatureSaysKeyOrderIncluded() {
    assertJson(
        "{\"type\":\"estate\",\"owned\":[2201,7],\"workspaceImage\":\"" + IMAGE + "\"}",
        new Estate(List.of(2201L, 7L), IMAGE));
    assertJson(
        "{\"type\":\"take\",\"rowId\":2201,\"spec\":{\"image\":\""
            + IMAGE
            + "\",\"env\":{\"QITS_WORKSPACE_DAEMON_WORKSPACE_ID\":\"main\",\"TZ\":\"Europe/Berlin\"},"
            + "\"mounts\":[{\"volume\":\"WORKSPACE\",\"target\":\"/workspace\"},"
            + "{\"volume\":\"DOT_CLAUDE\",\"target\":\"/claude-home\"},"
            + "{\"volume\":\"M2\",\"target\":\"/caches/m2\"},"
            + "{\"volume\":\"PNPM\",\"target\":\"/caches/pnpm\"}],"
            + "\"labels\":{\"qits.repository\":\"qits-qits\",\"qits.workspace\":\"main\"},"
            + "\"memoryLimit\":\"4g\",\"memorySwapLimit\":\"8g\",\"pidsLimit\":4096,\"cpus\":\"2\","
            + "\"oomScoreAdj\":600,\"init\":true}}",
        new Take(2201L, fullSpec()));
    assertJson(
        "{\"type\":\"take\",\"rowId\":7,\"spec\":{\"image\":\""
            + IMAGE
            + "\",\"env\":{},\"mounts\":[],\"labels\":{},\"memoryLimit\":null,"
            + "\"memorySwapLimit\":null,\"pidsLimit\":null,\"cpus\":null,\"oomScoreAdj\":null,"
            + "\"init\":false}}",
        new Take(7L, bareSpec()));
    assertJson("{\"type\":\"stop\",\"rowId\":2201}", new Stop(2201L));
    assertJson("{\"type\":\"delete\",\"rowId\":2201}", new Delete(2201L));
    assertJson("{\"type\":\"probeLogin\"}", new ProbeLogin());
    assertJson("{\"type\":\"healthCheck\"}", new HealthCheck());
    assertJson(
        "{\"type\":\"inventory\",\"containers\":[{\"rowId\":2201,\"running\":true,"
            + "\"specHash\":\"sha256:abc\"},{\"rowId\":7,\"running\":false,\"specHash\":null}],"
            + "\"volumes\":[2201,7],\"dotClaudeVolume\":\"qits-workspaces-runner-dot-claude-1a2b3c4d\"}",
        new Inventory(
            List.of(new HeldContainer(2201L, true, "sha256:abc"), new HeldContainer(7L, false, null)),
            List.of(2201L, 7L),
            "qits-workspaces-runner-dot-claude-1a2b3c4d"));
    assertJson(
        "{\"type\":\"launched\",\"rowId\":2201,\"containerId\":\"4f6c1e0b2a9d\"}",
        new Launched(2201L, "4f6c1e0b2a9d"));
    assertJson(
        "{\"type\":\"launchFailed\",\"rowId\":2201,\"detail\":\"no space\"}",
        new LaunchFailed(2201L, "no space"));
    assertJson("{\"type\":\"stopped\",\"rowId\":2201}", new Stopped(2201L));
    assertJson("{\"type\":\"deleted\",\"rowId\":2201}", new Deleted(2201L));
    assertJson("{\"type\":\"exited\",\"rowId\":2201,\"exitCode\":137}", new Exited(2201L, 137));
    assertJson(
        "{\"type\":\"loginState\",\"claude\":\"PRESENT\",\"kimi\":\"ABSENT\","
            + "\"checkedAt\":\"2026-10-05T04:38:19Z\"}",
        new LoginState(LoginPresence.PRESENT, LoginPresence.ABSENT, "2026-10-05T04:38:19Z"));
    assertJson(
        "{\"type\":\"healthChecked\",\"ok\":true,\"detail\":\"fine\"}",
        new HealthChecked(true, "fine"));
    assertJson(
        "{\"type\":\"healthCheck\",\"requestId\":\"req-1\"}", new HealthCheck("req-1"));
    assertJson(
        "{\"type\":\"healthChecked\",\"ok\":false,\"detail\":\"session: timed out after 1s\","
            + "\"requestId\":\"req-1\",\"checks\":["
            + "{\"name\":\"docker\",\"ok\":true,\"detail\":\"docker 27.3.1\","
            + "\"data\":{\"serverVersion\":\"27.3.1\"}},"
            + "{\"name\":\"nodeInventory\",\"ok\":true,\"detail\":\"1 container, 0 volumes\","
            + "\"data\":{\"containers\":[{\"name\":\"qits-workspaces-runner-ws-2201-1a2b3c4d\","
            + "\"rowId\":2201,\"finishedAt\":null}],\"runnerContainer\":null}},"
            + "{\"name\":\"session\",\"ok\":false,\"detail\":\"timed out after 1s\",\"data\":{}}]}",
        fullHealthChecked());
  }

  private static void assertJson(String expected, WorkspacesRunnerMessage frame) {
    assertEquals(expected, Json.write(CODEC.encode(frame)), () -> "spelling of " + frame);
  }

  // --- tolerance and refusals --------------------------------------------------------------------

  @Test
  void unknownFieldsAreIgnoredAndEitherBoxForANumberIsFine() {
    Map<String, Object> wire = new LinkedHashMap<>(CODEC.encode(new Exited(2201L, 1)));
    wire.put("rowId", 2201); // an Integer, as Jackson decodes a small number
    wire.put("signal", "SIGKILL");
    wire.put("nested", Map.of("x", 1));
    assertEquals(new Exited(2201L, 1), CODEC.decode(wire));

    Map<String, Object> take = new LinkedHashMap<>(CODEC.encode(new Take(7L, fullSpec())));
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = new LinkedHashMap<>((Map<String, Object>) take.get("spec"));
    spec.put("network", "qits-net"); // a field this version cannot express is not obeyed
    spec.put("hostDockerSocket", true);
    take.put("spec", spec);
    assertEquals(new Take(7L, fullSpec()), CODEC.decode(take));
  }

  @Test
  void anUnknownTypeIsTheBasesTypedError() {
    RunnerDecodeException e =
        assertThrows(
            RunnerDecodeException.class, () -> CODEC.decode(Map.of("type", "migrate", "rowId", 1)));
    assertEquals(RunnerDecodeException.Reason.UNKNOWN_TYPE, e.reason());
  }

  @Test
  void anEstateOrInventoryWithoutItsListsIsMalformedNeverEmpty() {
    // Read as empty, an absent `owned` would have the runner remove every workspace on its node.
    assertMalformed(Map.of("type", "estate", "workspaceImage", IMAGE));
    assertMalformed(Map.of("type", "estate", "owned", List.of("2201"), "workspaceImage", IMAGE));
    assertMalformed(Map.of("type", "inventory", "volumes", List.of()));
    assertMalformed(Map.of("type", "inventory", "containers", List.of()));
  }

  @Test
  void aMissingRowIdSpecOrImageAndAnUnknownMountAreMalformed() {
    assertMalformed(Map.of("type", "stop"));
    assertMalformed(Map.of("type", "launched", "rowId", "2201", "containerId", "x"));
    assertMalformed(Map.of("type", "take", "rowId", 7));
    assertMalformed(Map.of("type", "take", "rowId", 7, "spec", Map.of("env", Map.of())));
    assertMalformed(
        Map.of(
            "type",
            "take",
            "rowId",
            7,
            "spec",
            Map.of(
                "image",
                IMAGE,
                "mounts",
                List.of(Map.of("volume", "DOCKER_SOCKET", "target", "/var/run/docker.sock")))));
    assertMalformed(
        Map.of(
            "type",
            "take",
            "rowId",
            7,
            "spec",
            Map.of("image", IMAGE, "mounts", List.of(Map.of("volume", "M2", "target", "rel")))));
  }

  @Test
  void theOriginalHealthFramesStillReadAndAreStillWrittenByteForByte() {
    // What a peer older than requestId/checks sends: both read, with the new fields absent.
    assertEquals(new HealthCheck(null), CODEC.decode(Map.of("type", "healthCheck")));
    assertEquals(
        new HealthChecked(true, "fine", null, List.of()),
        CODEC.decode(Map.of("type", "healthChecked", "ok", true, "detail", "fine")));
    // And what it reads from this one when the new fields are absent is the original spelling.
    assertJson("{\"type\":\"healthCheck\"}", new HealthCheck());
    assertJson(
        "{\"type\":\"healthChecked\",\"ok\":true,\"detail\":\"fine\"}",
        new HealthChecked(true, "fine", null, List.of()));
    // A check with no data reads as one with an empty object.
    assertEquals(
        new HealthChecked(
            false, null, "req-4", List.of(new CheckResult("docker", false, null, Map.of()))),
        CODEC.decode(
            Map.of(
                "type",
                "healthChecked",
                "requestId",
                "req-4",
                "checks",
                List.of(Map.of("name", "docker")))));
  }

  @Test
  void aLoginPresenceThisVersionDoesNotKnowReadsAsUnknown() {
    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("type", "loginState");
    wire.put("claude", "EXPIRED");
    wire.put("checkedAt", "2026-10-05T04:38:19Z");
    LoginState state = assertInstanceOf(LoginState.class, CODEC.decode(wire));
    assertEquals(LoginPresence.UNKNOWN, state.claude());
    assertEquals(LoginPresence.UNKNOWN, state.kimi());
  }

  private static void assertMalformed(Map<String, Object> wire) {
    RunnerDecodeException e =
        assertThrows(RunnerDecodeException.class, () -> CODEC.decode(wire), wire::toString);
    assertEquals(RunnerDecodeException.Reason.MALFORMED, e.reason(), wire::toString);
  }

  /**
   * The JSON a map becomes, in its own iteration order — what Jackson and Vert.x both write for
   * these shapes. Test-only and deliberately minimal: strings, numbers, booleans, null, maps, lists.
   */
  static final class Json {

    static String write(Object value) {
      StringBuilder out = new StringBuilder();
      write(value, out);
      return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
      switch (value) {
        case null -> out.append("null");
        case String s -> {
          out.append('"');
          for (char c : s.toCharArray()) {
            switch (c) {
              case '"' -> out.append("\\\"");
              case '\\' -> out.append("\\\\");
              case '\n' -> out.append("\\n");
              default -> out.append(c);
            }
          }
          out.append('"');
        }
        case Number n -> out.append(n);
        case Boolean b -> out.append(b);
        case Map<?, ?> m -> {
          out.append('{');
          boolean first = true;
          for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) {
              out.append(',');
            }
            first = false;
            write(String.valueOf(e.getKey()), out);
            out.append(':');
            write(e.getValue(), out);
          }
          out.append('}');
        }
        case List<?> l -> {
          out.append('[');
          for (int i = 0; i < l.size(); i++) {
            if (i > 0) {
              out.append(',');
            }
            write(l.get(i), out);
          }
          out.append(']');
        }
        default -> throw new IllegalArgumentException("not a wire value: " + value.getClass());
      }
    }
  }
}
