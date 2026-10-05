package eu.wohlben.qits.workspacesrunner.health;

import eu.wohlben.qits.runner.toolkit.RunnerSession;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The registry of a runner's {@link RunnerHealthCheck}s and the one way they are run: in
 * registration order, each on its own thread under its own deadline, so a check that throws or
 * hangs is reported as failed — with the error — and every other check is still run and reported.
 * A health check is a diagnosis; one broken probe must not hide the rest of the picture.
 *
 * <p>Like the interface, free of workspace types, for the same move into qits-runner-javalib.
 */
public final class HealthChecks {

  /** One check's outcome, under its name. */
  public record Outcome(String name, boolean ok, String detail, Map<String, Object> data) {}

  private final List<RunnerHealthCheck> checks = new CopyOnWriteArrayList<>();
  private final Duration defaultDeadline;

  /** Daemon threads: a check that ignores its interrupt never holds the process. */
  private final ExecutorService pool =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "runner-health-check");
            t.setDaemon(true);
            return t;
          });

  public HealthChecks(Duration defaultDeadline) {
    this.defaultDeadline = defaultDeadline;
  }

  /** Add a check; it runs after every check registered before it. A name is unique. */
  public HealthChecks register(RunnerHealthCheck check) {
    for (RunnerHealthCheck existing : checks) {
      if (existing.name().equals(check.name())) {
        throw new IllegalArgumentException("a health check named " + check.name() + " exists");
      }
    }
    checks.add(check);
    return this;
  }

  /** The registered checks' names, in order. */
  public List<String> names() {
    return checks.stream().map(RunnerHealthCheck::name).toList();
  }

  /** Run every check, in order, each under its deadline; never throws for a check's failure. */
  public List<Outcome> run(RunnerSession session) {
    List<Outcome> outcomes = new ArrayList<>();
    for (RunnerHealthCheck check : checks) {
      outcomes.add(runOne(check, session));
    }
    return outcomes;
  }

  /** Stop the threads; for a suite's teardown and the runner's end. */
  public void close() {
    pool.shutdownNow();
  }

  private Outcome runOne(RunnerHealthCheck check, RunnerSession session) {
    Duration deadline = check.deadline() == null ? defaultDeadline : check.deadline();
    Future<RunnerHealthCheck.Result> running;
    try {
      running = pool.submit(() -> check.run(session));
    } catch (RuntimeException closed) {
      return failed(check, "could not run: " + closed.getMessage());
    }
    try {
      RunnerHealthCheck.Result result = running.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
      if (result == null) {
        return failed(check, "the check answered nothing");
      }
      return new Outcome(
          check.name(), result.ok(), result.detail(), result.data() == null ? Map.of() : result.data());
    } catch (TimeoutException e) {
      running.cancel(true);
      return failed(check, "timed out after " + deadline.toMillis() + " ms");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      return failed(check, "failed: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
    } catch (InterruptedException e) {
      running.cancel(true);
      Thread.currentThread().interrupt();
      return failed(check, "interrupted");
    }
  }

  private static Outcome failed(RunnerHealthCheck check, String detail) {
    return new Outcome(check.name(), false, detail, Map.of());
  }
}
