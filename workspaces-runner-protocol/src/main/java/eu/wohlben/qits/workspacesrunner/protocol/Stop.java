package eu.wohlben.qits.workspacesrunner.protocol;

/**
 * qits-workspaces → runner: stop this row's workspace container, keeping it and its volume. Pushed
 * to the owner and answered {@link Stopped}, idempotently — an absent container still answers.
 */
public record Stop(long rowId) implements WorkspacesRunnerMessage {}
