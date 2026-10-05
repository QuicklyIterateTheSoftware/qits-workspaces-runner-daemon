package eu.wohlben.qits.workspacesrunner.protocol;

/**
 * Runner → qits-workspaces: the answer to {@link HealthCheck}. {@code ok} lifts a new runner's
 * quarantine; {@code detail} says what was done, or what failed.
 */
public record HealthChecked(boolean ok, String detail) implements WorkspacesRunnerMessage {}
