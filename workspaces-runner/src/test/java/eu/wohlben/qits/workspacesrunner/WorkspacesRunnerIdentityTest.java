package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.KnownRunnerNamespaces;
import eu.wohlben.qits.runner.toolkit.OwnResources;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The node namespace the feature promises, spelled out: every name and label the workspaces runner
 * uses, derived from its identity — and the identity is the one the toolkit lists for this kind,
 * so no other runner on the node can reach into it.
 */
class WorkspacesRunnerIdentityTest {

  private static final RunnerIdentity IDENTITY = WorkspacesRunnerIdentity.IDENTITY;
  private static final String ID = "1a2b3c4d-0000-4000-8000-000000000001";

  @Test
  void theIdentityHasTheRootsTheToolkitKnowsForThisKind() {
    assertTrue(KnownRunnerNamespaces.WORKSPACES.matches(IDENTITY));
    assertEquals(WorkspacesRunnerBinary.VERSION, IDENTITY.version());
    assertEquals("qits/qits-workspaces-runner", IDENTITY.imageRepository());
    assertEquals("/var/lib/qits-workspaces-runner", IDENTITY.defaultStateDir());
    assertEquals("/workspaces/api/runners/" + ID + "/register", IDENTITY.registerPath(ID));
    assertEquals("qits-workspaces-runner", IDENTITY.serviceName());
  }

  @Test
  void everyNameOnTheNodeIsTheFeaturesSpelling() {
    assertEquals(
        "qits-workspaces-runner-1a2b3c4d-2026.1005.1", IDENTITY.containerName(ID, "2026.1005.1"));
    assertEquals("qits-workspaces-runner-state-1a2b3c4d", IDENTITY.stateVolume(ID));
    assertEquals("qits.workspaces.runner.process", IDENTITY.processLabel());
    assertEquals("qits.workspaces.runner.version", IDENTITY.versionLabel());
    assertEquals("qits.workspaces.runner.", IDENTITY.driverNamespace());
    assertEquals("QITS_WORKSPACES_RUNNER_URL", IDENTITY.env("URL"));

    WorkspaceNames names = new WorkspaceNames(IDENTITY, ID);
    assertEquals("qits-workspaces-runner-ws-2201-1a2b3c4d", names.container(2201));
    assertEquals("qits-workspaces-runner-ws-2201-1a2b3c4d", names.workspaceVolume(2201));
    assertEquals("qits-workspaces-runner-health-check-1a2b3c4d", names.healthCheck());
    assertEquals(
        "qits-workspaces-runner-dot-claude-1a2b3c4d", IDENTITY.nodeVolume(ID, "dot-claude"));

    OwnResources own =
        new OwnResources(IDENTITY, ID, new RunnerDocker(IDENTITY, "docker", Duration.ofSeconds(1)));
    assertEquals(
        Map.of("qits.workspaces.runner", ID, "qits.workspaces.runner.workspace", "2201"),
        own.labels("2201"));
  }
}
