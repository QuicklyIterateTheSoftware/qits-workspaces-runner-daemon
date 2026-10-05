package eu.wohlben.qits.workspacesrunner.protocol;

/** Runner → qits-workspaces: the answer to {@link Stop}; the row's container is not running. */
public record Stopped(long rowId) implements WorkspacesRunnerMessage {}
