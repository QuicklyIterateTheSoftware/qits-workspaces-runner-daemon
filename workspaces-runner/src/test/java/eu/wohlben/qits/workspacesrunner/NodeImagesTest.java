package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * {@link NodeImages}' one pull per image (qits-1114): callers that arrive while a pull is under way
 * wait for it rather than start their own, and a pull that ended — either way — is forgotten, so
 * the next caller tries again. Driven through the {@code shared} seam; the docker it is given is
 * never asked.
 */
class NodeImagesTest {

  private static final String IMAGE = "registry.example:5000/qits/workspace:2026.1004.2";

  private final NodeImages images = new NodeImages(null);

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
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (b.getState() != Thread.State.WAITING && System.nanoTime() < until) {
      Thread.sleep(5);
    }
    release.countDown();
    a.join(10_000);
    b.join(10_000);

    assertEquals(1, pulls.get(), "one pull for both callers");
    assertEquals(Optional.of("Error: pull access denied"), first.get());
    assertEquals(first.get(), second.get(), "the waiter gets the puller's answer");
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

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
