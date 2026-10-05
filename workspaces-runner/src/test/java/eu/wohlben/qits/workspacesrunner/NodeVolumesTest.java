package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import eu.wohlben.qits.runner.toolkit.OwnResources;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import eu.wohlben.qits.workspacesrunner.protocol.Mount;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** {@link NodeVolumes}: every name from the identity, every volume made labelled. */
@EnabledOnOs(OS.LINUX)
class NodeVolumesTest {

  private static final RunnerIdentity IDENTITY = WorkspacesRunnerIdentity.IDENTITY;

  /** Another runner kind sharing the node — its own roots, so its own names and labels. */
  private static final RunnerIdentity OTHER =
      new RunnerIdentity(
          "other",
          "QITS_OTHER_RUNNER_",
          "qits.other.runner",
          "qits-other-runner",
          "qits/qits-other-runner",
          "/var/lib/qits-other-runner",
          "/other/api/runners/{id}/register",
          "qits-other-runner",
          "1.0.0",
          1,
          new RunnerVocabulary("heldOthers", "adoptedOthers"),
          "work");

  @TempDir Path dir;

  @Test
  void theFourNamesComeFromTheIdentityAndTheRunnersId() {
    NodeVolumes volumes = new NodeVolumes(IDENTITY, "1a2b3c4d5e6f");

    assertEquals("qits-workspaces-runner-ws-42-1a2b3c4d", volumes.name(Mount.Volume.WORKSPACE, 42));
    assertEquals("qits-workspaces-runner-dot-claude-1a2b3c4d", volumes.dotClaude());
    assertEquals("qits-workspaces-runner-m2-1a2b3c4d", volumes.name(Mount.Volume.M2, 42));
    assertEquals("qits-workspaces-runner-pnpm-1a2b3c4d", volumes.name(Mount.Volume.PNPM, 42));
    assertEquals(volumes.dotClaude(), volumes.name(Mount.Volume.DOT_CLAUDE, 42));
  }

  @Test
  void aRowIsReadBackOnlyFromANameThisRunnerWouldGiveIt() {
    NodeVolumes volumes = new NodeVolumes(IDENTITY, "r1");

    assertEquals(Optional.of(42L), volumes.rowOf("qits-workspaces-runner-ws-42-r1"));
    assertEquals(Optional.empty(), volumes.rowOf("qits-workspaces-runner-ws-42-r2"));
    assertEquals(Optional.empty(), volumes.rowOf("qits-workspaces-runner-ws-42-r1-copy"));
    assertEquals(Optional.empty(), volumes.rowOf("qits-workspaces-runner-dot-claude-r1"));
    assertEquals(Optional.empty(), volumes.rowOf("qits-workspaces-runner-ws-x-r1"));
    assertEquals(Optional.empty(), volumes.rowOf("my-data"));
  }

  @Test
  void twoIdentitiesOnOneNodeMakeDisjointLabelledVolumes() throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    make(docker, IDENTITY, "r1");
    make(docker, OTHER, "r2");

    Map<String, Map<String, String>> made = docker.volumes();
    assertEquals(8, made.size(), made::toString);
    Set<String> ours = new HashSet<>();
    Set<String> theirs = new HashSet<>();
    made.forEach(
        (name, labels) -> {
          if (labels.containsKey("qits.workspaces.runner")) {
            ours.add(name);
          } else {
            theirs.add(name);
          }
        });
    assertEquals(
        Set.of(
            "qits-workspaces-runner-ws-7-r1",
            "qits-workspaces-runner-dot-claude-r1",
            "qits-workspaces-runner-m2-r1",
            "qits-workspaces-runner-pnpm-r1"),
        ours);
    assertEquals(
        Set.of(
            "qits-other-runner-ws-7-r2",
            "qits-other-runner-dot-claude-r2",
            "qits-other-runner-m2-r2",
            "qits-other-runner-pnpm-r2"),
        theirs);
    assertEquals(
        Map.of("qits.workspaces.runner", "r1", "qits.workspaces.runner.workspace", "7"),
        made.get("qits-workspaces-runner-ws-7-r1"),
        "a row's volume carries the work label");
    assertEquals(
        Map.of("qits.workspaces.runner", "r1"),
        made.get("qits-workspaces-runner-m2-r1"),
        "a node volume carries the ownership label alone");
    assertTrue(theirs.stream().allMatch(n -> made.get(n).containsKey("qits.other.runner")));
  }

  private static void make(FakeDocker docker, RunnerIdentity identity, String runnerId) {
    RunnerDocker runnerDocker = new RunnerDocker(identity, docker.binary(), Duration.ofSeconds(10));
    OwnResources own = new OwnResources(identity, runnerId, runnerDocker);
    NodeVolumes volumes = new NodeVolumes(identity, runnerId);
    for (Mount.Volume volume : Mount.Volume.values()) {
      assertTrue(
          volumes.ensure(runnerDocker.driver(), own, volume, 7, Duration.ofSeconds(10)).ok());
    }
  }
}
