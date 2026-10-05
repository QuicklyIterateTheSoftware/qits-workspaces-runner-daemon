package eu.wohlben.qits.workspacesrunner.protocol;

import java.util.List;

/**
 * Runner → qits-workspaces: what of the workspaces is on this node, read from docker under the
 * runner's own label. Sent right after the {@code Ack} and on every change; the server reconciles
 * its rows against it. Like {@link Estate}, it is sent only from a listing docker answered, and the
 * codec refuses a frame that carries no lists.
 *
 * @param containers every workspace container of this runner, running or not
 * @param volumes the rows whose workspace volume exists on the node
 * @param dotClaudeVolume the node's agent-home volume — what the operator's login command mounts —
 *     or null before the runner has one
 */
public record Inventory(List<HeldContainer> containers, List<Long> volumes, String dotClaudeVolume)
    implements WorkspacesRunnerMessage {

  public Inventory {
    containers = List.copyOf(containers);
    volumes = List.copyOf(volumes);
  }
}
