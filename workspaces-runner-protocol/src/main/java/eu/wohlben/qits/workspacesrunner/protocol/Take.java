package eu.wohlben.qits.workspacesrunner.protocol;

/**
 * qits-workspaces → runner: the answer to a {@code Reserve} when a queued row was claimed for this
 * runner. The row holds one of the runner's slots from the {@link Launched} on.
 *
 * @param rowId the workspace row, {@code workspace.id}
 * @param spec what to run — composed by the server from the row's facts
 */
public record Take(long rowId, WorkspaceSpec spec) implements WorkspacesRunnerMessage {}
