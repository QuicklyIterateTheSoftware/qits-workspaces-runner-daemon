package eu.wohlben.qits.workspacesrunner.protocol;

/**
 * Runner → qits-workspaces: a workspace container stopped by itself — nobody sent a {@link Stop} —
 * with this exit code. Its slot is free.
 */
public record Exited(long rowId, int exitCode) implements WorkspacesRunnerMessage {}
