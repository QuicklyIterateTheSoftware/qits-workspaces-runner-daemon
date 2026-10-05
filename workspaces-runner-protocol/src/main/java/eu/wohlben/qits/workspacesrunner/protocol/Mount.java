package eu.wohlben.qits.workspacesrunner.protocol;

/**
 * One logical mount of a {@link WorkspaceSpec}: WHICH of the runner's volumes, and where it lands.
 * The volume's name is never on the wire — the runner derives it from its own identity ({@code
 * qits-workspaces-runner-ws-<rowId>-<id8>} for {@link Volume#WORKSPACE}, {@code
 * qits-workspaces-runner-<dot-claude|m2|pnpm>-<id8>} for the node's shared ones), so a frame cannot
 * point a workspace at somebody else's volume.
 *
 * @param volume which volume
 * @param target the absolute path inside the container
 */
public record Mount(Volume volume, String target) {

  /** The four volumes a workspace container mounts. Spelled on the wire as the constant's name. */
  public enum Volume {
    /** The row's own {@code /workspace}, one per workspace row on this node. */
    WORKSPACE,
    /** The node's agent home ({@code /claude-home}), shared by this runner's workspaces. */
    DOT_CLAUDE,
    /** The node's maven cache. */
    M2,
    /** The node's pnpm store. */
    PNPM
  }

  public Mount {
    if (volume == null) {
      throw new IllegalArgumentException("a mount names no volume");
    }
    if (target == null || !target.startsWith("/")) {
      throw new IllegalArgumentException("a mount's target is not an absolute path: " + target);
    }
  }
}
