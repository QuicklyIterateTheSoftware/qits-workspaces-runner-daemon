package eu.wohlben.qits.workspacesrunner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The pin resolves — the guard on this module's resource-filtering block, which is the one thing
 * about {@link WorkspacesRunnerBinary} that can silently break. It asserts the shape and not a
 * value: the version is whatever stamped this tree, a CalVer after a release and a {@code
 * -SNAPSHOT} before the first one.
 */
class WorkspacesRunnerBinaryTest {

  @Test
  void theVersionIsFilteredInAndIsACalVerOrASnapshot() {
    String version = WorkspacesRunnerBinary.VERSION;
    assertFalse(version.isBlank(), "the version is blank");
    assertFalse(
        version.startsWith("$"),
        () -> "an unfiltered ${project.version} reached the jar: " + version);
    assertTrue(
        version.matches("[0-9][0-9.]*[0-9](-SNAPSHOT)?"),
        () -> "not a CalVer — is resource filtering still on? got: " + version);
  }

  @Test
  void theNamesAreTheOnesThePlatformAddressesTheRunnerBy() {
    // Literals, because each is a cross-repository contract: release.yml publishes the image under
    // this repository, qits-workspaces serves the socket and the register door at these paths, and
    // every label and name on a node is derived from these roots.
    assertEquals("qits/qits-workspaces-runner", WorkspacesRunnerProtocol.IMAGE_REPOSITORY);
    assertEquals("/workspaces/runners/socket", WorkspacesRunnerProtocol.SOCKET_PATH);
    assertEquals(
        "/workspaces/api/runners/{id}/register", WorkspacesRunnerProtocol.REGISTER_PATH);
    assertEquals("workspaces", WorkspacesRunnerProtocol.KIND);
    assertEquals("QITS_WORKSPACES_RUNNER_", WorkspacesRunnerProtocol.ENV_PREFIX);
    assertEquals("qits.workspaces.runner", WorkspacesRunnerProtocol.LABEL_ROOT);
    assertEquals("qits-workspaces-runner", WorkspacesRunnerProtocol.NAME_PREFIX);
    assertEquals("/var/lib/qits-workspaces-runner", WorkspacesRunnerProtocol.STATE_DIR);
    assertEquals("qits-workspaces-runner", WorkspacesRunnerProtocol.SERVICE_NAME);
    assertEquals("workspace", WorkspacesRunnerProtocol.WORK_LABEL_SUFFIX);
    assertEquals("heldWorkspaces", WorkspacesRunnerProtocol.VOCABULARY.heldField());
    assertEquals("adoptedWorkspaces", WorkspacesRunnerProtocol.VOCABULARY.adoptedField());
  }
}
