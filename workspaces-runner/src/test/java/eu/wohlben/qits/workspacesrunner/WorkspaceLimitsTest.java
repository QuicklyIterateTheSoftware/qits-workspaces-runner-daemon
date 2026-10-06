package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkspaceLimits}: the two variables as the operator spelled them, announced only when set,
 * and refused at startup — naming the variable — where docker would refuse them on every take.
 */
class WorkspaceLimitsTest {

  private static final String MEMORY = "QITS_WORKSPACES_RUNNER_WORKSPACE_MEMORY_LIMIT";
  private static final String SWAP = "QITS_WORKSPACES_RUNNER_WORKSPACE_MEMORY_SWAP_LIMIT";

  @Test
  void unsetOrBlankAnnouncesNothing() throws Exception {
    assertEquals(WorkspaceLimits.NONE, parse(Map.of()));
    assertEquals(WorkspaceLimits.NONE, parse(Map.of(MEMORY, "", SWAP, "  ")));
    assertEquals(Map.of(), WorkspaceLimits.NONE.announced());
  }

  @Test
  void eachSetValueIsAnnouncedTrimmedUnderItsWireName() throws Exception {
    WorkspaceLimits both = parse(Map.of(MEMORY, " 8g ", SWAP, "12g"));
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("workspaceMemoryLimit", "8g");
    expected.put("workspaceMemorySwapLimit", "12g");
    assertEquals(expected, both.announced());
    assertEquals(List.copyOf(expected.keySet()), List.copyOf(both.announced().keySet()));

    assertEquals(Map.of("workspaceMemoryLimit", "6g"), parse(Map.of(MEMORY, "6g")).announced());
    assertEquals(
        Map.of("workspaceMemorySwapLimit", "16g"), parse(Map.of(SWAP, "16g")).announced());
  }

  @Test
  void dockersSizeSpellingsAreAcceptedAsDockerAcceptsThem() throws Exception {
    for (String size : List.of("8g", "8G", "8GiB", "8gb", "1.5g", "8192m", "4294967296", "512 m")) {
      assertEquals(size, parse(Map.of(MEMORY, size)).memoryLimit(), size);
    }
    assertEquals("-1", parse(Map.of(MEMORY, "8g", SWAP, "-1")).memorySwapLimit(), "unlimited");
    assertEquals(
        "8192m", parse(Map.of(MEMORY, "8g", SWAP, "8192m")).memorySwapLimit(), "no swap");
  }

  @Test
  void aSizeDockerWouldRefuseFailsNamingTheVariable() {
    for (String bad : List.of("banana", "8x", "-1", "g", "8 gigs")) {
      String said = refused(Map.of(MEMORY, bad));
      assertTrue(said.startsWith(MEMORY + " is not a docker memory size"), said);
      assertTrue(said.contains("'" + bad + "'"), said);
    }
    String said = refused(Map.of(SWAP, "banana"));
    assertTrue(said.startsWith(SWAP + " is not a docker memory size"), said);
  }

  @Test
  void aSwapTotalBelowTheMemoryCapIsRefusedBecauseItIsATotal() {
    String said = refused(Map.of(MEMORY, "8g", SWAP, "4g"));
    assertTrue(said.startsWith(SWAP + " is memory plus swap"), said);
    assertTrue(said.contains(MEMORY), said);
  }

  private static WorkspaceLimits parse(Map<String, String> env) throws RunnerSettings.Invalid {
    return WorkspaceLimits.parse(WorkspacesRunnerIdentity.IDENTITY, new HashMap<>(env)::get);
  }

  private static String refused(Map<String, String> env) {
    return assertThrows(RunnerSettings.Invalid.class, () -> parse(env)).getMessage();
  }
}
