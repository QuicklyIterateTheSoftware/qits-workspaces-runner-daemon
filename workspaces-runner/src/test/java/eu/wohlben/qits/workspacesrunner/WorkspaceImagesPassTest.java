package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.HousekeepingContext;
import eu.wohlben.qits.runner.toolkit.OwnResources;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorkspaceImagesPass} against a node's images: only the workspace image's repository, never
 * the current tag or the same image under another tag, never one a container was created from, and
 * nothing at all before the first estate or while the current image is not on the node yet.
 */
@EnabledOnOs(OS.LINUX)
class WorkspaceImagesPassTest {

  private static final RunnerIdentity IDENTITY = WorkspacesRunnerIdentity.IDENTITY;
  private static final String REPO = "registry.qits.example.eu/qits/workspace";
  private static final String CURRENT = REPO + ":2026.1004.3";

  @TempDir Path dir;
  private FakeDocker docker;
  private RunnerDocker runnerDocker;

  @BeforeEach
  void setUp() throws Exception {
    docker = new FakeDocker(dir.resolve("docker"));
    runnerDocker = new RunnerDocker(IDENTITY, docker.binary(), Duration.ofSeconds(10));
    docker.answerFor("image-inspect", CURRENT, 0, "sha256:cccc\n", "");
    docker.answer(
        "image-ls",
        0,
        String.join(
            "\n",
            CURRENT + "|sha256:cccc",
            REPO + ":current-alias|sha256:cccc",
            REPO + ":2026.1004.1|sha256:aaaa",
            REPO + ":2026.1004.2|sha256:bbbb",
            REPO + ":<none>|sha256:dddd",
            "registry.qits.example.eu/qits/workspace-base:2026.1|sha256:eeee",
            "registry.qits.example.eu/qits/qits-workspaces-runner:2026.1|sha256:ffff",
            "postgres:16|sha256:9999")
            + "\n",
        "");
    // 2026.1004.2 is still the image of a stopped workspace container.
    docker.answerFor("ps", "ancestor=sha256:bbbb", 0, "c0ffee\n", "");
  }

  @Test
  void itRemovesOnlyOldUnusedTagsOfTheWorkspaceRepository() {
    new WorkspaceImagesPass(runnerDocker, () -> CURRENT).run(context());

    List<List<String>> removals =
        docker.calls().stream()
            .filter(c -> c.size() >= 2 && c.get(0).equals("image") && c.get(1).equals("rm"))
            .toList();
    assertEquals(List.of(List.of("image", "rm", REPO + ":2026.1004.1")), removals);
    assertTrue(
        docker.calls().stream().noneMatch(c -> c.contains("prune")), "never a prune of its own");
  }

  @Test
  void whileTheCurrentImageIsNotHereYetItRemovesNothing() throws Exception {
    // The estate named it, but its pull has not landed: the old tags hold the layers it reuses.
    docker.answerFor("image-inspect", CURRENT, 1, "", "Error: No such image: " + CURRENT);

    new WorkspaceImagesPass(runnerDocker, () -> CURRENT).run(context());

    assertTrue(
        docker.calls().stream()
            .noneMatch(c -> c.size() >= 2 && c.get(0).equals("image") && c.get(1).equals("rm")),
        "no image rm before the current image is here: " + docker.calls());
  }

  @Test
  void beforeTheFirstEstateItTouchesNothing() {
    new WorkspaceImagesPass(runnerDocker, () -> null).run(context());
    assertEquals(List.of(), docker.calls());
  }

  @Test
  void theRepositoryOfAReferenceKeepsARegistryPort() {
    assertEquals(
        "registry.example:5000/qits/workspace",
        WorkspaceImagesPass.repository("registry.example:5000/qits/workspace:1"));
    assertEquals("qits/workspace", WorkspaceImagesPass.repository("qits/workspace@sha256:abc"));
    assertEquals(
        "registry.example:5000/qits/workspace",
        WorkspaceImagesPass.repository("registry.example:5000/qits/workspace"));
  }

  private HousekeepingContext context() {
    return new HousekeepingContext(
        IDENTITY,
        "r1",
        runnerDocker.driver(),
        new OwnResources(IDENTITY, "r1", runnerDocker),
        runnerDocker.deadline(),
        dir,
        Clock.systemUTC(),
        Logger.getLogger(WorkspaceImagesPassTest.class));
  }
}
