package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;

/**
 * Every name the workspaces runner gives a thing on its node, derived from its {@link
 * RunnerIdentity} and never from a frame — so no frame can point the runner at a container or a
 * volume it does not own.
 *
 * <ul>
 *   <li>a workspace's container and its {@code /workspace} volume: {@code
 *       qits-workspaces-runner-ws-<rowId>-<id8>}, keyed on the workspace ROW id, so two
 *       repositories' {@code main} workspaces can never share a volume;
 *   <li>the health check's throwaway container: {@code qits-workspaces-runner-health-check-<id8>}.
 * </ul>
 *
 * <p>The node volumes ({@code dot-claude}, {@code m2}, {@code pnpm}) are qits-858's and join here.
 */
final class WorkspaceNames {

  /** The purpose segment of a workspace's container and volume, before its row id. */
  static final String WORKSPACE = "ws";

  /** The purpose segment of the health check's container. */
  static final String HEALTH_CHECK = "health-check";

  private final RunnerIdentity identity;
  private final String runnerId;

  WorkspaceNames(RunnerIdentity identity, String runnerId) {
    this.identity = identity;
    this.runnerId = runnerId;
  }

  /** {@code qits-workspaces-runner-ws-<rowId>-<id8>}: the row's container. */
  String container(long rowId) {
    return identity.nodeName(runnerId, WORKSPACE + "-" + rowId);
  }

  /** {@code qits-workspaces-runner-ws-<rowId>-<id8>}: the row's {@code /workspace} volume. */
  String workspaceVolume(long rowId) {
    return identity.nodeVolume(runnerId, WORKSPACE + "-" + rowId);
  }

  /** {@code qits-workspaces-runner-health-check-<id8>}. */
  String healthCheck() {
    return identity.nodeName(runnerId, HEALTH_CHECK);
  }
}
