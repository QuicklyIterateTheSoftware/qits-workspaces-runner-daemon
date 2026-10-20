package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 *
 * <p><b>A caller that must answer within a deadline waits a bounded time</b> ({@link
 * #ensure(RunnerSession, String, Duration)}). A cold pull of a new workspace image runs for many
 * minutes under the toolkit's own pull deadline, far past a health check's; a health check that
 * waited it out was cancelled at its deadline and reported "timed out", and the server quarantined
 * a node whose only fault was a release it had not finished downloading (qits-959). Such a caller
 * now hears {@link Pulling} instead, and — exactly as for an interrupted one — the pull is not
 * cancelled: the launch or the pre-pull waiting on it still gets its answer.
 */
final class NodeImages {

  /** Whether an image is here, as a caller with a deadline hears it. */
  sealed interface Ensured permits Here, Pulling, Failed {}

  /** On the node: here already, or pulled within the wait. */
  record Here() implements Ensured {}

  /** Not here yet, and a pull of it is under way past the wait — not a failure of anything. */
  record Pulling() implements Ensured {}

  /** Not here, and its pull failed (or the wait was interrupted); {@code detail} says why. */
  record Failed(String detail) implements Ensured {}

  static final Ensured HERE = new Here();

  static final Ensured PULLING = new Pulling();

  private final RunnerDocker docker;

  /**
   * The pulls under way, by image reference; each is removed once it has completed. A completed
   * entry still in the map counts as absent too — its pull thread has not removed it yet, and the
   * next caller to arrive replaces it rather than reading its stale answer (qits-1447).
   */
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
   * Whether {@code image} is here, waiting at most {@code wait} for its pull: started (or joined)
   * exactly as {@link #ensure(RunnerSession, String)} does, and left running when the wait ends
   * first — {@link Pulling}, never a cancelled pull.
   */
  Ensured ensure(RunnerSession session, String image, Duration wait) {
    if (present(docker, image)) {
      return HERE;
    }
    return shared(image, () -> session.registry().pull(image), wait);
  }

  /** Whether a pull of {@code image} is under way on this node right now. */
  boolean pulling(String image) {
    // A completed entry still in the map is not a pull under way (qits-1447): its thread is on
    // its way to removing it, and the next caller treats it as absent too.
    CompletableFuture<Optional<String>> running = inFlight.get(image);
    return running != null && !running.isDone();
  }

  /**
   * {@code pull}'s answer for {@code image}, started on the pull thread unless a pull of the same
   * image is already under way, in which case that one's answer. Package-private as the suite's
   * seam.
   */
  Optional<String> shared(String image, Supplier<Optional<String>> pull) {
    return switch (await(image, join(image, pull), null)) {
      case Failed failed -> Optional.of(failed.detail());
      case Here _ -> Optional.empty();
      // Unreachable: only a bounded wait times out.
      case Pulling _ -> Optional.of("the pull of " + image + " is still under way");
    };
  }

  /** {@link #shared(String, Supplier)}, waiting at most {@code wait}; the suite's seam too. */
  Ensured shared(String image, Supplier<Optional<String>> pull, Duration wait) {
    return await(image, join(image, pull), wait);
  }

  /** The pull of {@code image} under way, or {@code pull} started as it. */
  private CompletableFuture<Optional<String>> join(
      String image, Supplier<Optional<String>> pull) {
    CompletableFuture<Optional<String>> mine = new CompletableFuture<>();
    // A completed future counts as absent (qits-1447): start() completes before it removes, and a
    // caller arriving in between would otherwise read the ended pull's stale answer.
    CompletableFuture<Optional<String>> running =
        inFlight.compute(image, (k, v) -> v == null || v.isDone() ? mine : v);
    if (running == mine) {
      // Started only once it is in the map, so its removal can never precede its insertion.
      start(image, mine, pull);
    }
    return running;
  }

  /**
   * {@code running}'s answer, waiting for it unbounded when {@code wait} is null. Neither a wait
   * that runs out nor an interrupt cancels it: the future is every caller's, not this one's.
   */
  private static Ensured await(
      String image, CompletableFuture<Optional<String>> running, Duration wait) {
    try {
      Optional<String> failure =
          wait == null ? running.get() : running.get(wait.toNanos(), TimeUnit.NANOSECONDS);
      return failure.<Ensured>map(Failed::new).orElse(HERE);
    } catch (TimeoutException stillPulling) {
      return PULLING;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new Failed("interrupted while waiting for the pull of " + image);
    } catch (ExecutionException failed) {
      return new Failed("the pull of " + image + " failed: " + failed.getCause());
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
            // join() replaces a completed entry still in the map (qits-1447), and this remove is
            // value-conditional, so a late one never evicts the newer pull that replaced it.
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
