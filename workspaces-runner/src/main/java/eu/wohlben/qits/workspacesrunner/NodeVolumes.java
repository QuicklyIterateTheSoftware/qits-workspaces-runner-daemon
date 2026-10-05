package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import eu.wohlben.qits.runner.toolkit.OwnResources;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.workspacesrunner.protocol.Mount;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The four volumes a workspace container mounts, named from the runner's {@link RunnerIdentity} and
 * never from a frame — a {@link Mount} says WHICH volume, this class says which one that is here:
 *
 * <ul>
 *   <li>{@link Mount.Volume#WORKSPACE} → {@code qits-workspaces-runner-ws-<rowId>-<id8>}, the row's
 *       own, labelled {@code qits.workspaces.runner=<id>} and {@code
 *       qits.workspaces.runner.workspace=<rowId>} ({@link OwnResources#labels});
 *   <li>{@link Mount.Volume#DOT_CLAUDE}, {@link Mount.Volume#M2}, {@link Mount.Volume#PNPM} → {@code
 *       qits-workspaces-runner-<dot-claude|m2|pnpm>-<id8>}, the node's, shared by this runner's
 *       workspaces only and labelled {@code qits.workspaces.runner=<id>} alone: they belong to no
 *       row, so no estate ever removes them — only the decommission does, with the node's agent
 *       login on the first of them.
 * </ul>
 *
 * <p><b>Created labelled on first use</b>, and every use asks again: docker's {@code volume create}
 * is its own no-op for a name that exists. That matters for the agent home in particular — a {@code
 * docker run -v <name>:…} against a volume that is not there yet creates it <em>unlabelled</em>, and
 * an unlabelled volume is one the decommission never finds; so the runner makes it before the
 * operator's login command can.
 */
final class NodeVolumes {

  static final String DOT_CLAUDE = "dot-claude";
  static final String M2 = "m2";
  static final String PNPM = "pnpm";

  private final RunnerIdentity identity;
  private final String runnerId;
  private final WorkspaceNames names;

  NodeVolumes(RunnerIdentity identity, String runnerId) {
    this.identity = identity;
    this.runnerId = runnerId;
    this.names = new WorkspaceNames(identity, runnerId);
  }

  /** {@code qits-workspaces-runner-ws-<rowId>-<id8>}: the row's {@code /workspace}. */
  String workspace(long rowId) {
    return names.workspaceVolume(rowId);
  }

  /** {@code qits-workspaces-runner-dot-claude-<id8>}: the node's agent home and login. */
  String dotClaude() {
    return identity.nodeVolume(runnerId, DOT_CLAUDE);
  }

  /** {@code qits-workspaces-runner-m2-<id8>}: the node's maven cache. */
  String m2() {
    return identity.nodeVolume(runnerId, M2);
  }

  /** {@code qits-workspaces-runner-pnpm-<id8>}: the node's pnpm store. */
  String pnpm() {
    return identity.nodeVolume(runnerId, PNPM);
  }

  /** The volume a logical mount names on this node, for the row {@code rowId}. */
  String name(Mount.Volume volume, long rowId) {
    return switch (volume) {
      case WORKSPACE -> workspace(rowId);
      case DOT_CLAUDE -> dotClaude();
      case M2 -> m2();
      case PNPM -> pnpm();
    };
  }

  /**
   * Make the volume a logical mount names, labelled; idempotent.
   *
   * @param own the runner's own resources, whose {@link OwnResources#labels} a row's volume carries
   */
  ContainersDriver.OpResult ensure(
      ContainersDriver driver, OwnResources own, Mount.Volume volume, long rowId, Duration deadline) {
    Map<String, String> labels =
        volume == Mount.Volume.WORKSPACE
            ? own.labels(Long.toString(rowId))
            : Map.of(identity.ownerLabel(), runnerId);
    return driver.ensureVolume(new VolumeSpec(name(volume, rowId)), labels, deadline);
  }

  /** The agent home, made labelled when it is not there yet. */
  ContainersDriver.OpResult ensureDotClaude(ContainersDriver driver, Duration deadline) {
    return driver.ensureVolume(
        new VolumeSpec(dotClaude()), Map.of(identity.ownerLabel(), runnerId), deadline);
  }

  /**
   * The row whose workspace volume {@code name} is, or empty when it is no row's: the name is read
   * back and then re-derived, so only a name this runner would itself give a row's volume answers.
   */
  Optional<Long> rowOf(String name) {
    String prefix = identity.namePrefix() + "-" + WorkspaceNames.WORKSPACE + "-";
    if (name == null || !name.startsWith(prefix)) {
      return Optional.empty();
    }
    String rest = name.substring(prefix.length());
    int dash = rest.indexOf('-');
    if (dash <= 0) {
      return Optional.empty();
    }
    try {
      long rowId = Long.parseLong(rest.substring(0, dash));
      return workspace(rowId).equals(name) ? Optional.of(rowId) : Optional.empty();
    } catch (NumberFormatException notARow) {
      return Optional.empty();
    }
  }
}
