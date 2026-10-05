package eu.wohlben.qits.workspacesrunner.protocol;

/** Runner → qits-workspaces: the row's container is running, as {@code containerId}. */
public record Launched(long rowId, String containerId) implements WorkspacesRunnerMessage {}
