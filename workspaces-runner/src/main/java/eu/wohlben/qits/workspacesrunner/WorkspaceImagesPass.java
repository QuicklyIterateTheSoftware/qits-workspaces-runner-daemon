package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.runner.toolkit.HousekeepingContext;
import eu.wohlben.qits.runner.toolkit.HousekeepingPass;
import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import java.util.function.Supplier;

/**
 * Workspace images this node no longer needs: every tag of the workspace image's repository other
 * than the one the latest {@code estate} named, that no container — running or stopped, this
 * runner's or anybody's — was created from. Every workspace-image release leaves one behind on
 * every node, and nothing else removes it.
 *
 * <p>The three belts of the toolkit's {@code RunnerImages}, for the same reasons: the current
 * image is skipped by reference and by id; an image some container uses is skipped by {@code docker
 * ps -a --filter ancestor=}; and {@code docker image rm} without {@code -f} refuses one a container
 * uses anyway. Never {@code system prune}, never {@code image prune -a}. Before the first {@code
 * estate} the current image is unknown, and the pass removes nothing.
 *
 * <p><b>Nothing goes before the current image is here.</b> The latest {@code estate} names the
 * current image whether or not the node has it yet, and while it is still being pulled — or after
 * its pull failed — the old tags are the only workspace images on the node proven to run, and they
 * hold the base layers the pull is about to reuse. Removing them then is what turned a slow pull
 * into a failed launch (qits-1114). So a pass whose current image does not inspect removes nothing,
 * scheduled or run from the {@code estate}: a failed pull prunes nothing, whichever path ran.
 */
final class WorkspaceImagesPass implements HousekeepingPass {

  private final RunnerDocker docker;
  private final Supplier<String> currentImage;

  /**
   * @param currentImage the latest {@code estate}'s {@code workspaceImage}, or null before one
   */
  WorkspaceImagesPass(RunnerDocker docker, Supplier<String> currentImage) {
    this.docker = docker;
    this.currentImage = currentImage;
  }

  @Override
  public String name() {
    return "workspace-images";
  }

  @Override
  public void run(HousekeepingContext context) {
    String current = currentImage.get();
    if (current == null || current.isBlank()) {
      return;
    }
    String repository = repository(current);
    RunnerDocker.Result currentId =
        docker.run(LifecycleArgv.imageInspect(docker.binary(), current));
    if (!currentId.ok()) {
      context
          .log()
          .infof(
              "workspaces-runner removes no workspace image: the current one, %s, is not here yet",
              current);
      return;
    }
    // A second tag of the current image is the same bytes, and is kept with it.
    String keepId = currentId.stdout().strip();
    RunnerDocker.Result listed = docker.run(LifecycleArgv.imageList(docker.binary()));
    if (!listed.ok() || listed.stdoutTruncated()) {
      context.log().warnf("workspaces-runner could not list the node's images: %s", listed.detail());
      return;
    }
    int removed = 0;
    for (String line : listed.stdout().split("\\R")) {
      String[] fields = line.strip().split("\\|", -1);
      if (fields.length < 2) {
        continue;
      }
      String ref = fields[0].strip();
      String id = fields[1].strip();
      int colon = ref.lastIndexOf(':');
      if (colon <= 0 || !ref.substring(0, colon).equals(repository) || ref.endsWith(":<none>")) {
        continue;
      }
      if (ref.equals(current) || (!keepId.isEmpty() && sameImage(id, keepId))) {
        continue;
      }
      if (remove(context, ref, id)) {
        removed++;
      }
    }
    if (removed > 0) {
      context.log().infof("workspaces-runner removed %d old workspace image(s)", removed);
    }
  }

  private boolean remove(HousekeepingContext context, String ref, String id) {
    try {
      RunnerDocker.Result users = docker.run(LifecycleArgv.psAncestor(docker.binary(), id));
      if (!users.ok()) {
        context
            .log()
            .warnf("workspaces-runner left %s: could not ask which containers use it", ref);
        return false;
      }
      if (!users.stdout().isBlank()) {
        return false;
      }
      RunnerDocker.Result gone = docker.run(LifecycleArgv.imageRm(docker.binary(), ref));
      if (!gone.ok()) {
        context.log().warnf("workspaces-runner could not remove %s: %s", ref, gone.detail());
        return false;
      }
      context.log().infof("workspaces-runner removed %s (an old workspace image)", ref);
      return true;
    } catch (IllegalArgumentException notAnImage) {
      context.log().warnf("workspaces-runner ignored an image reference: %s", notAnImage.getMessage());
      return false;
    }
  }

  /** {@code registry.qits.<d>/qits/workspace} of {@code registry.qits.<d>/qits/workspace:<tag>}. */
  static String repository(String image) {
    int at = image.indexOf('@');
    String named = at >= 0 ? image.substring(0, at) : image;
    int colon = named.lastIndexOf(':');
    int slash = named.lastIndexOf('/');
    return colon > slash ? named.substring(0, colon) : named;
  }

  /** Two image ids, either possibly without its {@code sha256:} prefix or truncated. */
  static boolean sameImage(String a, String b) {
    String x = a.startsWith("sha256:") ? a.substring(7) : a;
    String y = b.startsWith("sha256:") ? b.substring(7) : b;
    return !x.isEmpty() && !y.isEmpty() && (x.startsWith(y) || y.startsWith(x));
  }
}
