package eu.wohlben.qits.workspacesrunner.protocol;

/**
 * qits-workspaces → runner: remove this row's workspace container AND its workspace volume, so
 * nothing of the row is left on the node. Answered {@link Deleted}, idempotently.
 */
public record Delete(long rowId) implements WorkspacesRunnerMessage {}
