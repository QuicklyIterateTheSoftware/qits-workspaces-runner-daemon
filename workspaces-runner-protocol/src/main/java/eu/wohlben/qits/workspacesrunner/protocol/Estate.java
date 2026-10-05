package eu.wohlben.qits.workspacesrunner.protocol;

import java.util.List;

/**
 * qits-workspaces → runner: the desired state of this runner's node. Sent right after the {@code
 * Ack} and again whenever either value changes.
 *
 * <p><b>{@code owned} is the server's list, and it wins.</b> It names the ACTIVE workspace rows
 * whose {@code runner_id} is this runner; the runner removes the containers (and, at its next
 * housekeeping pass, the workspace volumes) in its own label namespace whose row is not in it. So
 * it is sent only from a successful read, never as an empty list on error — and the codec refuses
 * a frame that carries no list at all rather than reading the absence as "owns nothing".
 *
 * @param owned the workspace row ids this runner owns
 * @param workspaceImage the public reference of the pinned workspace image ({@code
 *     registry.qits.<d>/qits/workspace:<pin>}) — the one the health check, the login probe and the
 *     operator's login command run, and the one housekeeping keeps
 */
public record Estate(List<Long> owned, String workspaceImage) implements WorkspacesRunnerMessage {

  public Estate {
    owned = List.copyOf(owned);
  }
}
