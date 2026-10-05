package eu.wohlben.qits.workspacesrunner.protocol;

/** Runner → qits-workspaces: the answer to {@link Delete}; nothing of the row is on the node. */
public record Deleted(long rowId) implements WorkspacesRunnerMessage {}
