package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.install.InstallScript;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The install script, as qits-workspaces will render it for THIS runner: qits-runner-toolkit's
 * template ({@code InstallScript.script}) filled from {@link WorkspacesRunnerIdentity#IDENTITY}.
 * There is no copy of the template here, and its contract — the {@code docker run} equals the
 * toolkit's successor argv — is the toolkit's {@code InstallScriptContractTest}. What this adds is
 * the workspaces spelling, and the one property a workspaces node depends on: <b>a re-run never
 * removes a workspace container or volume</b>, because the only thing the script selects is the
 * runner's own process label.
 */
@EnabledOnOs(OS.LINUX)
class WorkspacesInstallScriptTest {

  private static final RunnerIdentity IDENTITY = WorkspacesRunnerIdentity.IDENTITY;
  private static final String REGISTRY = "registry.qits.example.eu";
  private static final String ID = "3f2a9c1e-0000-4000-8000-000000000001";
  private static final String ID8 = "3f2a9c1e";
  private static final String VERSION = WorkspacesRunnerBinary.VERSION;
  private static final String IMAGE = REGISTRY + "/qits/qits-workspaces-runner:" + VERSION;
  private static final InstallScript.InstallPage PAGE =
      new InstallScript.InstallPage("the Workspaces UI's Runners page", List.of());

  /**
   * A docker that records every argv, one line each, and answers {@code ps} with a container per
   * label it was asked for: the runner's own for the process label, a WORKSPACE container for the
   * ownership label — so a script that selected by the latter would be seen removing it.
   */
  private static final String STUB =
      """
      printf 'docker %s\\n' "$*" >> "$W/calls.log"
      if [ "$1" = "--config" ]; then shift 2; fi
      case "$1" in
        login) cat > /dev/null ;;
        ps)
          for a in "$@"; do
            case "$a" in
              "label=qits.workspaces.runner.process=$ID") echo runnercontainer1 ;;
              "label=qits.workspaces.runner=$ID") echo workspacecontainer1 ;;
            esac
          done
          ;;
        run) [ "$2" = "-d" ] && echo 4f6c1e0b2a9d ;;
      esac
      exit 0
      """;

  @TempDir Path work;

  @Test
  void itRendersWithTheWorkspacesNamesAndParses() throws Exception {
    String script = InstallScript.script(IDENTITY, PAGE, REGISTRY);

    assertFalse(script.contains("{{"), script);
    assertTrue(script.contains("  image='" + IMAGE + "'\n"), script);
    for (String name :
        List.of(
            "QITS_WORKSPACES_RUNNER_URL",
            "QITS_WORKSPACES_RUNNER_ID",
            "QITS_WORKSPACES_RUNNER_REGISTRATION_TOKEN",
            "QITS_WORKSPACES_RUNNER_SLOTS")) {
      assertTrue(script.contains("require " + name + " \"${" + name + ":-}\""), name);
    }
    assertTrue(script.contains("name=\"qits-workspaces-runner-$id8-$version\""), script);
    assertTrue(script.contains("volume=\"qits-workspaces-runner-state-$id8\""), script);
    assertTrue(
        script.contains(
            "--filter \"label=qits.workspaces.runner.process=$QITS_WORKSPACES_RUNNER_ID\""),
        script);
    assertTrue(script.contains("paste the whole line from the Workspaces UI's Runners page."));
    for (String ci : List.of("QITS_CI_RUNNER_", "qits-ci-runner", "qits.ci.runner", "qits_tok_")) {
      assertFalse(script.contains(ci), ci);
    }

    Path file = work.resolve("runner-install.sh");
    Files.writeString(file, script);
    assertEquals(0, sh(List.of("sh", "-n", file.toString()), Map.of("PATH", "/usr/bin:/bin"), null));
  }

  @Test
  void aReRunRemovesOnlyTheRunnersOwnContainerNeverAWorkspaceOrAVolume() throws Exception {
    Path stubs = Files.createDirectories(work.resolve("stubs"));
    Path docker = stubs.resolve("docker");
    Files.writeString(
        docker, "#!/bin/sh\nW='" + work + "'\nID='" + ID + "'\n" + STUB, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwxr-xr-x"));
    Path script = work.resolve("runner-install.sh");
    Files.writeString(script, InstallScript.script(IDENTITY, PAGE, REGISTRY));

    int exit =
        sh(
            List.of("sh"),
            Map.of(
                "PATH", stubs + ":/usr/bin:/bin",
                "QITS_WORKSPACES_RUNNER_URL", "https://workspaces.qits.example.eu",
                "QITS_WORKSPACES_RUNNER_ID", ID,
                "QITS_WORKSPACES_RUNNER_REGISTRATION_TOKEN", "qits_tok_FIXTURE",
                "QITS_WORKSPACES_RUNNER_SLOTS", "2"),
            script);
    List<String> calls = Files.readAllLines(work.resolve("calls.log"));
    assertEquals(0, exit, String.join("\n", calls));

    List<String> listings = calls.stream().filter(c -> c.startsWith("docker ps")).toList();
    assertEquals(
        List.of("docker ps -aq --filter label=qits.workspaces.runner.process=" + ID),
        listings,
        "the only selection is the runner's own process label");
    List<String> removals =
        calls.stream()
            .filter(c -> c.startsWith("docker rm") || c.startsWith("docker volume"))
            .toList();
    assertEquals(List.of("docker rm -f runnercontainer1"), removals);
    assertTrue(
        calls.contains(
            "docker run --rm --entrypoint rm -v qits-workspaces-runner-state-"
                + ID8
                + ":/var/lib/qits-workspaces-runner "
                + IMAGE
                + " -f /var/lib/qits-workspaces-runner/client.json"),
        "the state volume is kept, its client.json removed: " + calls);
    assertTrue(
        calls.stream()
            .anyMatch(
                c ->
                    c.startsWith("docker run -d --name qits-workspaces-runner-" + ID8 + "-" + VERSION)
                        && c.contains("--label qits.workspaces.runner.process=" + ID)
                        && c.endsWith(IMAGE)),
        "the runner starts under its own name, label and image: " + calls);
  }

  private static int sh(List<String> command, Map<String, String> env, Path stdin)
      throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().clear();
    builder.environment().putAll(env);
    if (stdin != null) {
      builder.redirectInput(stdin.toFile());
    }
    Process process = builder.start();
    try (InputStream out = process.getInputStream()) {
      out.readAllBytes();
    }
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("the script did not finish: " + command);
    }
    return process.exitValue();
  }
}
