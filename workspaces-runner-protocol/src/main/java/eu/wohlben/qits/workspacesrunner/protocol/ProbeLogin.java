package eu.wohlben.qits.workspacesrunner.protocol;

/** qits-workspaces → runner: re-check the agent logins on the node now; answered {@link LoginState}. */
public record ProbeLogin() implements WorkspacesRunnerMessage {}
