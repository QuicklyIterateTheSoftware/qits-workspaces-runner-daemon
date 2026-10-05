package eu.wohlben.qits.workspacesrunner.protocol;

/**
 * Runner → qits-workspaces: the row's {@link Take} did not become a running container; {@code
 * detail} is what went wrong, in docker's own last words where there were any. The row holds no slot.
 */
public record LaunchFailed(long rowId, String detail) implements WorkspacesRunnerMessage {}
