package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacesrunner.protocol.Mount;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspaceSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkspaceLauncher#specHash}: the frame's own order never matters, every field does, and no
 * value can stand in for the next field.
 */
class WorkspaceSpecHashTest {

  private static final String IMAGE = "registry.qits.example.eu/qits/workspace:2026.1004.3";

  @Test
  void theHashIsTheCanonicalSpecsNotTheFramesOrder() {
    Map<String, String> ab = new LinkedHashMap<>();
    ab.put("A", "1");
    ab.put("B", "2");
    Map<String, String> ba = new LinkedHashMap<>();
    ba.put("B", "2");
    ba.put("A", "1");
    List<Mount> mounts =
        List.of(
            new Mount(Mount.Volume.WORKSPACE, "/workspace"),
            new Mount(Mount.Volume.DOT_CLAUDE, "/claude-home"));

    String one = WorkspaceLauncher.specHash(spec(ab, mounts, "4g"));
    String other = WorkspaceLauncher.specHash(spec(ba, mounts.reversed(), "4g"));

    assertEquals(one, other);
    assertTrue(one.matches("[0-9a-f]{64}"), one);
  }

  @Test
  void everyFieldMoves() {
    List<Mount> mounts = List.of(new Mount(Mount.Volume.WORKSPACE, "/workspace"));
    String base = WorkspaceLauncher.specHash(spec(Map.of("A", "1"), mounts, "4g"));

    assertNotEquals(base, WorkspaceLauncher.specHash(spec(Map.of("A", "2"), mounts, "4g")));
    assertNotEquals(base, WorkspaceLauncher.specHash(spec(Map.of("A", "1"), mounts, "8g")));
    assertNotEquals(base, WorkspaceLauncher.specHash(spec(Map.of("A", "1"), mounts, null)));
    assertNotEquals(
        base,
        WorkspaceLauncher.specHash(
            spec(Map.of("A", "1"), List.of(new Mount(Mount.Volume.WORKSPACE, "/w")), "4g")));
  }

  @Test
  void aValueCannotForgeTheNextField() {
    List<Mount> none = List.of();
    assertNotEquals(
        WorkspaceLauncher.specHash(spec(Map.of("A", "1\nenv.B=2"), none, null)),
        WorkspaceLauncher.specHash(spec(Map.of("A", "1", "B", "2"), none, null)));
  }

  private static WorkspaceSpec spec(Map<String, String> env, List<Mount> mounts, String memory) {
    return new WorkspaceSpec(
        IMAGE, env, mounts, Map.of("qits.workspace", "w"), memory, null, 512L, "2", 500, true);
  }
}
