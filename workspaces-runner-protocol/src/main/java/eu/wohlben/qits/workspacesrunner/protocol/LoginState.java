package eu.wohlben.qits.workspacesrunner.protocol;

/**
 * Runner → qits-workspaces: whether each agent CLI is logged in on this node. Presence only — the
 * probe never reads, copies or sends a credential, and the platform never distributes one.
 *
 * @param claude Claude Code's login
 * @param kimi Kimi Code's login
 * @param checkedAt an ISO-8601 instant: when the probe ran
 */
public record LoginState(LoginPresence claude, LoginPresence kimi, String checkedAt)
    implements WorkspacesRunnerMessage {

  public LoginState {
    claude = claude == null ? LoginPresence.UNKNOWN : claude;
    kimi = kimi == null ? LoginPresence.UNKNOWN : kimi;
  }
}
