package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.runner.toolkit.HousekeepingContext;
import eu.wohlben.qits.runner.toolkit.HousekeepingPass;
import eu.wohlben.qits.runner.toolkit.OwnResources;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The {@code /workspace} volumes of rows this runner no longer owns: every volume labelled {@code
 * qits.workspaces.runner=<id>} that is a row's ({@link NodeVolumes#rowOf}) and whose row the
 * latest {@code estate} of THIS session does not list, removed at the housekeeping pass after it.
 *
 * <p><b>Why here, and not with the containers at the {@code estate}.</b> A volume is the work —
 * possibly unpushed — and {@link OwnResources} never removes one; a container is cheap to make again
 * and goes at once. So the volume waits for the next pass, after the server has said the same thing
 * at least once more in between whenever it changed its mind, and docker's own refusal to remove a
 * volume a container still mounts is the last belt under it.
 *
 * <p>Nothing before an {@code estate} of this session: the keep set is the server's list, and with
 * no list there is nothing to compare against — never "owns nothing". The node volumes ({@code
 * dot-claude}, {@code m2}, {@code pnpm}) are no row's and are never candidates; neither is anything
 * without the runner's label.
 */
final class WorkspaceVolumesPass implements HousekeepingPass {

  private final NodeVolumes volumes;
  private final Supplier<Optional<Set<Long>>> keep;

  /**
   * @param keep the rows to keep — this session's {@code estate.owned} plus the takes it has not
   *     confirmed yet — or empty before this session's first {@code estate}
   */
  WorkspaceVolumesPass(NodeVolumes volumes, Supplier<Optional<Set<Long>>> keep) {
    this.volumes = volumes;
    this.keep = keep;
  }

  @Override
  public String name() {
    return "workspace-volumes";
  }

  @Override
  public void run(HousekeepingContext context) {
    Optional<Set<Long>> kept = keep.get();
    if (kept.isEmpty()) {
      context.log().infof("workspaces-runner keeps every workspace volume: no estate yet");
      return;
    }
    int removed = 0;
    for (String name : context.own().volumes()) {
      Optional<Long> row = volumes.rowOf(name);
      if (row.isEmpty() || kept.get().contains(row.get())) {
        continue;
      }
      ContainersDriver.OpResult gone = context.driver().removeVolume(name, context.deadline());
      if (gone.ok()) {
        removed++;
        context.log().infof("workspaces-runner removed %s: workspace %d is not its", name, row.get());
      } else {
        context.log().warnf("workspaces-runner could not remove %s: %s", name, gone.detail());
      }
    }
    if (removed > 0) {
      context.log().infof("workspaces-runner removed %d workspace volume(s)", removed);
    }
  }
}
