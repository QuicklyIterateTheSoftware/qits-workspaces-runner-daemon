package eu.wohlben.qits.workspacesrunner.health;

import eu.wohlben.qits.runner.toolkit.RunnerSession;
import java.time.Duration;
import java.util.Map;

/**
 * One named check a runner runs when its host asks for a health check. Deliberately free of any
 * workspace type — it speaks only the toolkit's {@link RunnerSession} and plain JSON-shaped data —
 * so it can move into qits-runner-javalib for every runner kind (CI's too) unchanged.
 *
 * <p>A check reports facts; it does not act on them. Whatever it writes to the node is its own
 * declared business and documented on the implementation: most checks only read.
 */
public interface RunnerHealthCheck {

  /** The stable name the report carries, lowercase-camel ({@code docker}, {@code session}). */
  String name();

  /**
   * How long {@link #run} may take before {@link HealthChecks} reports it timed out; null for the
   * registry's default.
   */
  default Duration deadline() {
    return null;
  }

  /**
   * Run the check. Throwing is allowed and is reported as a failed check carrying the error, so an
   * implementation need not guard against every failure of what it reads.
   */
  Result run(RunnerSession session) throws Exception;

  /**
   * A check's outcome.
   *
   * @param ok whether it found what it looks for
   * @param detail one line for a person
   * @param data JSON-shaped facts — strings, numbers, booleans, null, lists and maps of those
   */
  record Result(boolean ok, String detail, Map<String, Object> data) {

    public static Result ok(String detail, Map<String, Object> data) {
      return new Result(true, detail, data);
    }

    public static Result failed(String detail, Map<String, Object> data) {
      return new Result(false, detail, data);
    }
  }
}
