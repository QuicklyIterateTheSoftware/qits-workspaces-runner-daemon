package eu.wohlben.qits.workspacesrunner.protocol;

/**
 * qits-workspaces → runner: run the health check now — start the pinned workspace image under the
 * runner's label, see it running, remove it — and answer {@link HealthChecked}. A new runner is
 * quarantined until its first one passes.
 */
public record HealthCheck() implements WorkspacesRunnerMessage {}
