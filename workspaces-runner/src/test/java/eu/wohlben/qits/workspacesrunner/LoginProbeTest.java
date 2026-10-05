package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import eu.wohlben.qits.workspacesrunner.protocol.LoginPresence;
import eu.wohlben.qits.workspacesrunner.protocol.LoginState;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LoginProbe} against a node: the two argvs exactly, the three presences a run can mean, and
 * the account its output names never reaching the log.
 */
@EnabledOnOs(OS.LINUX)
class LoginProbeTest {

  private static final RunnerIdentity IDENTITY = WorkspacesRunnerIdentity.IDENTITY;
  private static final String IMAGE = "registry.qits.example.eu/qits/workspace:2026.1004.3";
  private static final Instant NOW = Instant.parse("2026-10-05T04:38:19Z");
  private static final String DOT_CLAUDE = "qits-workspaces-runner-dot-claude-r1";

  @TempDir Path dir;
  private FakeDocker docker;
  private RunnerDocker runnerDocker;
  private LoginProbe probe;

  @BeforeEach
  void setUp() throws Exception {
    docker = new FakeDocker(dir);
    runnerDocker = new RunnerDocker(IDENTITY, docker.binary(), Duration.ofSeconds(10));
    probe =
        new LoginProbe(
            IDENTITY,
            "r1",
            runnerDocker,
            new NodeVolumes(IDENTITY, "r1"),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void theProbesRunTheWorkspaceImageAgainstTheNodesAgentHomeExactly() throws Exception {
    claudeSays(0, "{\"loggedIn\": true}");
    kimiExits(0);

    probe.probe(runnerDocker.driver(), IMAGE);

    List<List<String>> runs = docker.calls("run");
    assertEquals(
        List.of(
            List.of(
                "run",
                "--rm",
                "--label",
                "qits.workspaces.runner=r1",
                "--user",
                WorkspaceLauncher.USER,
                "--entrypoint",
                "claude",
                "-v",
                DOT_CLAUDE + ":/claude-home",
                "-e",
                "HOME=/claude-home",
                "-e",
                "CLAUDE_CONFIG_DIR=/claude-home/.claude",
                IMAGE,
                "auth",
                "status"),
            List.of(
                "run",
                "--rm",
                "--label",
                "qits.workspaces.runner=r1",
                "--user",
                WorkspaceLauncher.USER,
                "--entrypoint",
                "bash",
                "-v",
                DOT_CLAUDE + ":/claude-home",
                "-e",
                "KIMI_CODE_HOME=/claude-home/.kimi-code",
                IMAGE,
                "-c",
                "test -f \"$KIMI_CODE_HOME/credentials\""
                    + " || test -f \"$KIMI_CODE_HOME/credentials/kimi-code.json\"")),
        runs);
    assertEquals(
        Map.of("qits.workspaces.runner", "r1"),
        docker.volumes().get(DOT_CLAUDE),
        "the agent home is made labelled before a probe mounts it");
  }

  @Test
  void loggedInTrueIsPresentAndTheKimiCredentialIsPresent() throws Exception {
    claudeSays(0, "{\"loggedIn\": true, \"authMethod\": \"claude.ai\"}");
    kimiExits(0);

    assertEquals(
        new LoginState(LoginPresence.PRESENT, LoginPresence.PRESENT, NOW.toString()),
        probe.probe(runnerDocker.driver(), IMAGE));
  }

  @Test
  void loggedInFalseIsAbsentWhateverTheExitCodeAndNoKimiCredentialIsAbsent() throws Exception {
    claudeSays(1, "{\n  \"loggedIn\": false\n}");
    kimiExits(1);

    assertEquals(
        new LoginState(LoginPresence.ABSENT, LoginPresence.ABSENT, NOW.toString()),
        probe.probe(runnerDocker.driver(), IMAGE));
  }

  @Test
  void aRunThatNeverReachedTheCliIsUnknownNotAbsent() throws Exception {
    docker.answerFor("run", "status", 125, "", "docker: Error response from daemon: no space left");
    docker.answerFor("run", LoginProbe.KIMI_CHECK, 127, "", "exec: \"bash\": not found");

    assertEquals(
        new LoginState(LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, NOW.toString()),
        probe.probe(runnerDocker.driver(), IMAGE));
  }

  @Test
  void withoutTheWorkspaceImageOnTheNodeNothingRunsAndTheLoginIsUnknown() throws Exception {
    docker.answerFor("image-inspect", IMAGE, 1, "", "Error: No such image: " + IMAGE);

    assertEquals(
        new LoginState(LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, NOW.toString()),
        probe.probe(runnerDocker.driver(), IMAGE));
    assertEquals(
        new LoginState(LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, NOW.toString()),
        probe.probe(runnerDocker.driver(), null),
        "before any estate");
    assertTrue(docker.calls("run").isEmpty(), docker.calls()::toString);
    assertTrue(docker.calls("pull").isEmpty(), "the probe never pulls: the estate does");
  }

  @Test
  void nothingTheProbesPrintReachesTheLog() throws Exception {
    String account = "secret-account@example.eu";
    claudeSays(
        0,
        "{\"loggedIn\": true, \"email\": \"" + account + "\", \"orgName\": \"secret-org\"}");
    docker.answerFor("run", LoginProbe.KIMI_CHECK, 1, "kimi says " + account, account);

    List<String> logged = Collections.synchronizedList(new ArrayList<>());
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            logged.add(
                record.getMessage()
                    + " "
                    + (record.getParameters() == null
                        ? ""
                        : Arrays.toString(record.getParameters())));
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    capture.setLevel(Level.ALL);
    java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
    java.util.logging.Logger ours = java.util.logging.Logger.getLogger("eu.wohlben");
    Level before = ours.getLevel();
    ours.setLevel(Level.ALL);
    root.addHandler(capture);
    try {
      LoginState state = probe.probe(runnerDocker.driver(), IMAGE);
      assertEquals(LoginPresence.PRESENT, state.claude());
      assertEquals(LoginPresence.ABSENT, state.kimi());
    } finally {
      root.removeHandler(capture);
      ours.setLevel(before);
    }

    assertFalse(logged.isEmpty(), "the capture saw the probe's own line");
    assertTrue(
        logged.stream().anyMatch(line -> line.contains("PRESENT") && line.contains("ABSENT")),
        logged::toString);
    for (String line : logged) {
      assertFalse(line.contains("secret"), "probe output reached the log: " + line);
    }
  }

  private void claudeSays(int code, String json) throws Exception {
    docker.answerFor("run", "status", code, json + "\n", "");
  }

  private void kimiExits(int code) throws Exception {
    docker.answerFor("run", LoginProbe.KIMI_CHECK, code, "", "");
  }
}
