package eu.wohlben.qits.workspacesrunner.protocol;

/**
 * qits-workspaces → runner: run every registered health check now and answer {@link
 * HealthChecked}. A new runner is quarantined until one passes. The checks include the self-test —
 * start the pinned workspace image under the runner's label, see it running, remove it — and
 * read-only looks at docker, the node's objects, the login and the session.
 *
 * @param requestId the asker's id, echoed in the answer; null when the asker sent none (a peer
 *     older than the field), and then left off the wire
 */
public record HealthCheck(String requestId) implements WorkspacesRunnerMessage {

  /** The original, id-less request. */
  public HealthCheck() {
    this(null);
  }
}
