package eu.wohlben.qits.workspacesrunner.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a workspace container is, as qits-workspaces composes it for a {@link Take}.
 *
 * <p><b>The shape is the boundary.</b> This arrives over a socket and becomes an argv to a
 * root-equivalent daemon, so it can express only what a workspace needs: an image, an environment,
 * the four logical mounts — whose volume NAMES the runner derives from its own identity, never from
 * the frame — labels, and limits. There is no network, no extra host, no docker socket, no user
 * and no free-form argv; a capability this record cannot express is a change to it, reviewed as one.
 * Labels under the runner's own namespace ({@code qits.workspaces.runner.}) are the runner's to add
 * and are refused by its driver when a spec carries one.
 *
 * <p>The maps keep their arrival order (never {@code Map.copyOf}, whose iteration order is salted
 * per JVM), so a hash over the canonical spec is the same in every process.
 *
 * @param image the public workspace image reference
 * @param env the container's environment. Carries no credential in qits-624; still never logged
 * @param mounts where each logical volume lands in the container
 * @param labels the server's own bookkeeping labels ({@code qits.repository}, {@code
 *     qits.workspace}, …), applied beside the runner's own
 * @param memoryLimit {@code --memory}, or null for none
 * @param memorySwapLimit {@code --memory-swap}, or null for none
 * @param pidsLimit {@code --pids-limit}, or null for none
 * @param cpus {@code --cpus}, or null for none
 * @param oomScoreAdj {@code --oom-score-adj}, or null for none
 * @param init whether tini is PID 1 ({@code --init})
 */
public record WorkspaceSpec(
    String image,
    Map<String, String> env,
    List<Mount> mounts,
    Map<String, String> labels,
    String memoryLimit,
    String memorySwapLimit,
    Long pidsLimit,
    String cpus,
    Integer oomScoreAdj,
    boolean init) {

  public WorkspaceSpec {
    if (image == null || image.isBlank()) {
      throw new IllegalArgumentException("a workspace spec names no image");
    }
    env = ordered(env);
    mounts = mounts == null ? List.of() : List.copyOf(mounts);
    labels = ordered(labels);
  }

  private static Map<String, String> ordered(Map<String, String> map) {
    return map == null || map.isEmpty()
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(map));
  }
}
