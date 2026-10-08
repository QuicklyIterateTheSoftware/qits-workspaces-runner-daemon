package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * An image on this node, pulled under the runner's own registry login when it is not here yet —
 * the step before a workspace launch, a health check and the {@code estate}'s pre-pull. Never a
 * pull of an image that is already here: a pinned workspace image does not move under its tag, and
 * a registry blip must not fail a start the node could make from what it has.
 *
 * <p><b>One pull per image at a time.</b> The launch, the health check and the pre-pull all ask for
 * the same workspace image, usually within seconds of a new {@code estate}. Each starting its own
 * {@code docker pull} meant three transfers of the same layers racing one another, each under its
 * own deadline and each failing on its own (qits-1114). So the first caller pulls and every caller
 * that arrives while it does waits for that same pull and gets its answer. The entry goes when the
 * pull ends, either way: a failed pull is not remembered, and the next caller tries again.
 */
final class NodeImages {

  private final RunnerDocker docker;

  /** The pulls under way, by image reference; each is removed once it has completed. */
  private final ConcurrentHashMap<String, CompletableFuture<Optional<String>>> inFlight =
      new ConcurrentHashMap<>();

  NodeImages(RunnerDocker docker) {
    this.docker = docker;
  }

  /** Empty when {@code image} is here (already, or pulled now); else why not. */
  Optional<String> ensure(RunnerSession session, String image) {
    if (present(docker, image)) {
      return Optional.empty();
    }
    return shared(image, () -> session.registry().pull(image));
  }

  /**
   * {@code pull}'s answer for {@code image}, run by this thread unless a pull of the same image is
   * already under way, in which case that one's answer. Package-private as the suite's seam.
   */
  Optional<String> shared(String image, Supplier<Optional<String>> pull) {
    CompletableFuture<Optional<String>> mine = new CompletableFuture<>();
    CompletableFuture<Optional<String>> running = inFlight.putIfAbsent(image, mine);
    if (running == null) {
      try {
        mine.complete(pull.get());
      } catch (RuntimeException failed) {
        mine.complete(Optional.of("the pull of " + image + " failed: " + failed));
      } finally {
        // Completed before removed: a waiter never holds a future nobody will complete.
        mine.complete(Optional.of("the pull of " + image + " ended without an answer"));
        inFlight.remove(image, mine);
      }
      return mine.join();
    }
    try {
      return running.get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Optional.of("interrupted while waiting for the pull of " + image);
    } catch (ExecutionException failed) {
      return Optional.of("the pull of " + image + " failed: " + failed.getCause());
    }
  }

  /** Whether {@code image} is on this node; a reference the belt refuses is not. */
  static boolean present(RunnerDocker docker, String image) {
    try {
      return docker.run(LifecycleArgv.imageInspect(docker.binary(), image)).ok();
    } catch (IllegalArgumentException refused) {
      return false;
    }
  }
}
