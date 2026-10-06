package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import eu.wohlben.qits.workspacesrunner.health.RunnerHealthCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorkspaceMemoryCheck} against a node with running workspaces faked through {@link
 * FakeDocker}: the always-ok answer whatever a workspace's own cgroup says, the cgroup v1 and
 * exec-failure tolerance, the node's {@code overcommitted} arithmetic (the running workspaces'
 * own caps against {@code MemTotal} — never an announced, node-wide limit; see the class
 * javadoc), and {@code /proc/meminfo}'s own parse.
 */
@EnabledOnOs(OS.LINUX)
class WorkspaceMemoryCheckTest {

  private static final RunnerIdentity IDENTITY = WorkspacesRunnerIdentity.IDENTITY;
  private static final String RUNNER_ID = "r1";
  private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

  @TempDir Path dir;
  @TempDir Path files;

  private FakeDocker docker;
  private RunnerDocker runnerDocker;
  private WorkspaceContainers containers;
  private WorkspaceNames names;

  @BeforeEach
  void setUp() throws Exception {
    docker = new FakeDocker(dir);
    runnerDocker = new RunnerDocker(IDENTITY, docker.binary(), Duration.ofSeconds(5));
    containers = new WorkspaceContainers(IDENTITY, RUNNER_ID, runnerDocker);
    names = new WorkspaceNames(IDENTITY, RUNNER_ID);
  }

  @Test
  void aContainerWithOomKillsAppearsInDetailAndDataAndTheCheckIsStillOk() throws Exception {
    running(7);
    docker.answerFor(
        "exec",
        names.container(7),
        0,
        "4294967296\n"
            + WorkspaceMemoryCheck.DELIMITER
            + "2147483648\n"
            + WorkspaceMemoryCheck.DELIMITER
            + "low 0\nhigh 0\nmax 0\noom 1\noom_kill 4\noom_group_kill 0\n",
        "");

    RunnerHealthCheck.Result result = check(2, meminfo("MemTotal:       16000000 kB\n")).run(null);

    assertTrue(result.ok());
    Map<?, ?> ws = (Map<?, ?>) ((List<?>) result.data().get("workspaces")).get(0);
    assertEquals(7L, ws.get("rowId"));
    assertEquals(names.container(7), ws.get("container"));
    assertEquals(4294967296L, ws.get("memoryMax"));
    assertEquals(2147483648L, ws.get("memoryCurrent"));
    assertEquals(4L, ws.get("oomKill"));
    assertEquals(1L, ws.get("oom"));
    assertTrue(result.detail().contains("ws 7: 4 oom kill(s) at 4.0 GiB cap"), result.detail());
  }

  @Test
  void aContainerWhoseExecFailsYieldsANullEntryAndTheCheckIsStillOk() throws Exception {
    running(9);
    docker.answerFor(
        "exec", names.container(9), 1, "", "Error: No such container: " + names.container(9));

    RunnerHealthCheck.Result result = check(1, meminfo("MemTotal: 1000 kB\n")).run(null);

    assertTrue(result.ok());
    Map<?, ?> ws = (Map<?, ?>) ((List<?>) result.data().get("workspaces")).get(0);
    assertNull(ws.get("memoryMax"));
    assertNull(ws.get("memoryCurrent"));
    assertNull(ws.get("oomKill"));
    assertNull(ws.get("oom"));
    assertTrue(((String) ws.get("note")).contains("docker exec failed"), ws.toString());
  }

  @Test
  void aCgroupV1ContainerHasNoCgroupV2FilesButStillAnswersOk() throws Exception {
    running(3);
    docker.answerFor("exec", names.container(3), 0, "", "");

    RunnerHealthCheck.Result result = check(1, meminfo("MemTotal: 16000000 kB\n")).run(null);

    assertTrue(result.ok());
    Map<?, ?> ws = (Map<?, ?>) ((List<?>) result.data().get("workspaces")).get(0);
    assertNull(ws.get("memoryMax"));
    assertTrue(((String) ws.get("note")).contains("no cgroup v2 memory files"), ws.toString());
  }

  @Test
  void overcommittedIsTrueWhenTheRunningWorkspacesCapsSumPastMemTotal() throws Exception {
    running(1);
    running(2);
    cgroup(names.container(1), 8_589_934_592L, 0, 0); // 8 GiB
    cgroup(names.container(2), 8_589_934_592L, 0, 0); // 8 GiB, 16 GiB summed

    RunnerHealthCheck.Result result =
        check(2, meminfo("MemTotal:       10000000 kB\n")).run(null); // ~9.5 GiB

    Map<?, ?> node = (Map<?, ?>) result.data().get("node");
    assertEquals(Boolean.TRUE, node.get("overcommitted"));
    assertTrue(result.detail().contains("node overcommitted"), result.detail());
  }

  @Test
  void overcommittedIsFalseWhenTheRunningWorkspacesCapsFitUnderMemTotal() throws Exception {
    running(1);
    cgroup(names.container(1), 1_073_741_824L, 0, 0); // 1 GiB

    RunnerHealthCheck.Result result = check(1, meminfo("MemTotal:       16000000 kB\n")).run(null);

    Map<?, ?> node = (Map<?, ?>) result.data().get("node");
    assertEquals(Boolean.FALSE, node.get("overcommitted"));
    assertTrue(result.detail().endsWith("no oom kills"), result.detail());
  }

  @Test
  void overcommittedIsNullWithoutARunningNumericCapOrAReadableMemTotal() throws Exception {
    // Nothing running at all: nothing to sum.
    RunnerHealthCheck.Result noWorkspaces = check(3, meminfo("MemTotal: 16000000 kB\n")).run(null);
    assertNull(((Map<?, ?>) noWorkspaces.data().get("node")).get("overcommitted"));

    // MemTotal itself unreadable.
    RunnerHealthCheck.Result noMemTotal = check(3, files.resolve("does-not-exist")).run(null);
    assertNull(((Map<?, ?>) noMemTotal.data().get("node")).get("overcommitted"));
  }

  @Test
  void meminfoParsesMemTotalAndSwapTotalToBytesAndLeavesOutAnythingElse() {
    Map<String, Long> parsed =
        WorkspaceMemoryCheck.meminfo(
            "MemTotal:       16384000 kB\nMemFree:          200000 kB\nSwapTotal:       2048000 kB\n");
    assertEquals(16_384_000L * 1024, parsed.get("MemTotal"));
    assertEquals(2_048_000L * 1024, parsed.get("SwapTotal"));
    assertNull(parsed.get("MemFree"));
  }

  // --- fixtures --------------------------------------------------------------------------------

  private WorkspaceMemoryCheck check(Integer slots, Path meminfo) {
    return new WorkspaceMemoryCheck(
        runnerDocker, containers, names, () -> slots, Clock.fixed(NOW, ZoneOffset.UTC), meminfo);
  }

  private Path meminfo(String content) throws Exception {
    Path f = Files.createTempFile(files, "meminfo", ".txt");
    Files.writeString(f, content);
    return f;
  }

  /** A row's container, running, under this runner's own labels — as the launch ladder names it. */
  private void running(long rowId) throws Exception {
    String name = names.container(rowId);
    docker.container(name, Map.of(IDENTITY.ownerLabel(), RUNNER_ID, IDENTITY.workLabel(), ""));
    docker.answerFor("inspect", name, 0, "c" + rowId + "|running|0|" + rowId + "|<no value>\n", "");
  }

  /** Scripts {@code container}'s exec answer as a cgroup v2 node would, with {@code oomKill} kills. */
  private void cgroup(String container, long memoryMax, long oom, long oomKill) throws Exception {
    docker.answerFor(
        "exec",
        container,
        0,
        memoryMax
            + "\n"
            + WorkspaceMemoryCheck.DELIMITER
            + "0\n"
            + WorkspaceMemoryCheck.DELIMITER
            + "oom "
            + oom
            + "\noom_kill "
            + oomKill
            + "\n",
        "");
  }
}
