package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
 * own deadline and each failing on its own (qits-1114). So the first caller starts a pull and every
 * caller, that one included, waits for it and gets its answer. The entry goes when the pull ends,
 * either way: a failed pull is not remembered, and the next caller tries again.
 *
 * <p><b>The pull runs on a thread of its own, never a caller's.</b> A caller can be interrupted —
 * the health checks cancel a check at its deadline — and the process seam answers an interrupt by
 * failing the call. Run on the caller's thread, that one interrupt would fail the pull every other
 * caller is waiting on, a launch included. So an interrupted caller stops waiting and answers its
 * own failure, and the pull goes on for the rest.
 */
final class NodeImages {

  private final RunnerDocker docker;

  /** The pulls under way, by image reference; each is removed once it has completed. */
  private final ConcurrentHashMap<String, CompletableFuture<Optional<String>>> inFlight =
      new ConcurrentHashMap<>();

  /** Where pulls run; daemon threads, so a pull never holds the process. */
  private final ExecutorService pulls =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "workspaces-runner-pull");
            t.setDaemon(true);
            return t;
          });

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
   * {@code pull}'s answer for {@code image}, started on the pull thread unless a pull of the same
   * image is already under way, in which case that one's answer. Package-private as the suite's
   * seam.
   */
  Optional<String> shared(String image, Supplier<Optional<String>> pull) {
    CompletableFuture<Optional<String>> mine = new CompletableFuture<>();
    CompletableFuture<Optional<String>> running = inFlight.putIfAbsent(image, mine);
    if (running == null) {
      // Started only once it is in the map, so its removal can never precede its insertion.
      running = mine;
      start(image, mine, pull);
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

  private void start(
      String image, CompletableFuture<Optional<String>> mine, Supplier<Optional<String>> pull) {
    Runnable run =
        () -> {
          try {
            mine.complete(pull.get());
          } catch (RuntimeException failed) {
            mine.complete(Optional.of("the pull of " + image + " failed: " + failed));
          } finally {
            // Completed before removed: a waiter never holds a future nobody will complete.
            mine.complete(Optional.of("the pull of " + image + " ended without an answer"));
            inFlight.remove(image, mine);
          }
        };
    try {
      pulls.execute(run);
    } catch (RejectedExecutionException closed) {
      mine.complete(Optional.of("the runner is closing; " + image + " was not pulled"));
      inFlight.remove(image, mine);
    }
  }

  /**
   * Stop the pull threads, giving a pull under way {@link WorkspacesRunnerDomain#CLOSE_GRACE} to
   * end before it is interrupted. For the domain's own close.
   */
  void close() {
    WorkspacesRunnerDomain.drain(pulls, WorkspacesRunnerDomain.CLOSE_GRACE);
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
