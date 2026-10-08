package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link NodeImages}' one pull per image (qits-1114): callers that arrive while a pull is under way
 * wait for it rather than start their own, an interrupted caller fails alone while the pull goes on,
 * and a pull that ended — either way — is forgotten, so the next caller tries again. A caller with
 * a deadline waits a bounded time and hears {@code PULLING}, never cancelling the pull (qits-959).
 * Driven through the {@code shared} seams; the docker it is given is never asked.
 */
class NodeImagesTest {

  private static final String IMAGE = "registry.example:5000/qits/workspace:2026.1004.2";

  private final NodeImages images = new NodeImages(null);

  @AfterEach
  void tearDown() {
    images.close();
  }

  @Test
  void twoCallersForTheSameImageShareOnePullAndItsAnswer() throws Exception {
    AtomicInteger pulls = new AtomicInteger();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Supplier<Optional<String>> pull =
        () -> {
          pulls.incrementAndGet();
          started.countDown();
          await(release);
          return Optional.of("Error: pull access denied");
        };
    AtomicReference<Optional<String>> first = new AtomicReference<>();
    AtomicReference<Optional<String>> second = new AtomicReference<>();
    Thread a = new Thread(() -> first.set(images.shared(IMAGE, pull)));
    a.start();
    assertTrue(started.await(10, TimeUnit.SECONDS), "the first caller pulls");
    Thread b = new Thread(() -> second.set(images.shared(IMAGE, pull)));
    b.start();
    // Parked either on the first caller's pull or, were it pulling itself, on the same latch.
    awaitWaiting(b);
    release.countDown();
    a.join(10_000);
    b.join(10_000);

    assertEquals(1, pulls.get(), "one pull for both callers");
    assertEquals(Optional.of("Error: pull access denied"), first.get());
    assertEquals(first.get(), second.get(), "the waiter gets the puller's answer");
  }

  @Test
  void aCallerInterruptedWhileWaitingFailsAloneAndThePullGoesOnForTheOthers() throws Exception {
    // The health check that started the pull is cancelled at its deadline; a launch waits on.
    AtomicInteger pulls = new AtomicInteger();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Supplier<Optional<String>> pull =
        () -> {
          pulls.incrementAndGet();
          started.countDown();
          await(release);
          return Thread.currentThread().isInterrupted()
              ? Optional.of("interrupted")
              : Optional.empty();
        };
    AtomicReference<Optional<String>> starter = new AtomicReference<>();
    AtomicReference<Optional<String>> waiter = new AtomicReference<>();
    Thread a = new Thread(() -> starter.set(images.shared(IMAGE, pull)));
    a.start();
    assertTrue(started.await(10, TimeUnit.SECONDS), "the first caller starts the pull");
    Thread b = new Thread(() -> waiter.set(images.shared(IMAGE, pull)));
    b.start();
    awaitWaiting(a);
    awaitWaiting(b);
    a.interrupt();
    a.join(10_000);
    assertEquals(
        Optional.of("interrupted while waiting for the pull of " + IMAGE),
        starter.get(),
        "the interrupted caller answers its own failure");
    assertTrue(b.isAlive(), "the other caller still waits on the pull");
    release.countDown();
    b.join(10_000);

    assertEquals(Optional.empty(), waiter.get(), "the pull itself was never interrupted");
    assertEquals(1, pulls.get(), "one pull for both callers");
  }

  @Test
  void aFailedPullIsNotRememberedAndTheNextCallerPullsAgain() {
    AtomicInteger pulls = new AtomicInteger();
    assertEquals(
        Optional.of("Error: pull access denied"),
        images.shared(
            IMAGE,
            () -> {
              pulls.incrementAndGet();
              return Optional.of("Error: pull access denied");
            }));
    assertEquals(
        Optional.empty(),
        images.shared(
            IMAGE,
            () -> {
              pulls.incrementAndGet();
              return Optional.empty();
            }));
    assertEquals(2, pulls.get(), "a second pull after the failed one");
  }

  @Test
  void aPullThatThrowsAnswersAFailureAndIsForgottenToo() {
    Optional<String> failed =
        images.shared(
            IMAGE,
            () -> {
              throw new IllegalStateException("docker vanished");
            });
    assertTrue(
        failed.isPresent() && failed.get().contains("docker vanished"),
        "why, not a throw: " + failed);
    assertEquals(Optional.empty(), images.shared(IMAGE, Optional::empty), "pulled again");
  }

  @Test
  void aBoundedWaitThatRunsOutAnswersPullingAndThePullGoesOnForAnUnboundedCaller()
      throws Exception {
    // The health check waits one docker deadline and moves on; the launch waits for the answer.
    AtomicInteger pulls = new AtomicInteger();
    CountDownLatch release = new CountDownLatch(1);
    Supplier<Optional<String>> pull =
        () -> {
          pulls.incrementAndGet();
          await(release);
          return Thread.currentThread().isInterrupted()
              ? Optional.of("interrupted")
              : Optional.empty();
        };
    assertEquals(NodeImages.PULLING, images.shared(IMAGE, pull, Duration.ofMillis(100)));
    assertTrue(images.pulling(IMAGE), "the pull is still under way");

    AtomicReference<Optional<String>> waiter = new AtomicReference<>();
    Thread b = new Thread(() -> waiter.set(images.shared(IMAGE, pull)));
    b.start();
    awaitWaiting(b);
    assertTrue(b.isAlive(), "the unbounded caller waits on the same pull");
    release.countDown();
    b.join(10_000);

    assertEquals(Optional.empty(), waiter.get(), "the pull was never cancelled nor interrupted");
    assertEquals(1, pulls.get(), "one pull for both callers");
  }

  @Test
  void aBoundedWaitThatOutlastsThePullAnswersItsOutcome() {
    assertEquals(
        NodeImages.HERE, images.shared(IMAGE, Optional::empty, Duration.ofSeconds(10)));
    assertEquals(
        new NodeImages.Failed("Error: pull access denied"),
        images.shared(
            IMAGE, () -> Optional.of("Error: pull access denied"), Duration.ofSeconds(10)));
  }

  private static void awaitWaiting(Thread t) throws InterruptedException {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (t.getState() != Thread.State.WAITING && System.nanoTime() < until) {
      Thread.sleep(5);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
