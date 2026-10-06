package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerExit;
import eu.wohlben.qits.runner.toolkit.RunnerLauncher;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Main}'s one decision: which invocations {@code RunnerLauncher} answers before Quarkus
 * starts, handed no Vert.x. Pinned here because it leans on the toolkit's order — health, the
 * version probe, helper mode — never touching the Vert.x it is passed; a toolkit that started to
 * would fail these with a {@code NullPointerException} long before an image ever shipped.
 */
class MainTest {

  @TempDir Path state;

  @Test
  void healthTheVersionProbeAndHelperModeAnswerBeforeQuarkusAndNothingElseDoes() {
    assertTrue(Main.answersBeforeQuarkus(new String[] {"health"}, name -> null));
    assertTrue(
        Main.answersBeforeQuarkus(
            new String[0], Map.of("QITS_WORKSPACES_RUNNER_PRINT_VERSION", "1")::get));
    assertTrue(
        Main.answersBeforeQuarkus(
            new String[0], Map.of("QITS_WORKSPACES_RUNNER_DECOMMISSION", "r1")::get));

    assertFalse(Main.answersBeforeQuarkus(new String[0], name -> null));
    assertFalse(
        Main.answersBeforeQuarkus(
            new String[0], Map.of("QITS_WORKSPACES_RUNNER_PRINT_VERSION", "0")::get));
    assertFalse(
        Main.answersBeforeQuarkus(
            new String[0], Map.of("QITS_WORKSPACES_RUNNER_URL", "https://x")::get));
  }

  @Test
  void theVersionProbeAndHealthNeedNoVertx() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int code =
        RunnerLauncher.main(
            WorkspacesRunnerIdentity.IDENTITY,
            WorkspacesRunnerDomain::new,
            null,
            new String[0],
            Map.of("QITS_WORKSPACES_RUNNER_PRINT_VERSION", "1")::get,
            new PrintStream(out, true, StandardCharsets.UTF_8));
    assertEquals(0, code);
    assertEquals(WorkspacesRunnerBinary.VERSION + "\n", out.toString(StandardCharsets.UTF_8));

    int health =
        RunnerLauncher.main(
            WorkspacesRunnerIdentity.IDENTITY,
            WorkspacesRunnerDomain::new,
            null,
            new String[] {"health"},
            Map.of("QITS_WORKSPACES_RUNNER_STATE_DIR", state.toString())::get,
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    assertEquals(1, health, "no heartbeat yet");
  }

  @Test
  void aWorkspaceLimitDockerWouldRefuseExitsTwoBeforeAnythingIsDialled() {
    int code =
        Main.run(
            null,
            new String[0],
            Map.of(
                    "QITS_WORKSPACES_RUNNER_URL", "https://workspaces.qits.example.eu",
                    "QITS_WORKSPACES_RUNNER_ID", "r1",
                    "QITS_WORKSPACES_RUNNER_WORKSPACE_MEMORY_LIMIT", "banana")
                ::get,
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    assertEquals(RunnerExit.MISCONFIGURED, code);
  }
}
