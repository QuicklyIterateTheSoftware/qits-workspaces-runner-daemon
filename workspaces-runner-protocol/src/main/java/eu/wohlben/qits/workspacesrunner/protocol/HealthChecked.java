package eu.wohlben.qits.workspacesrunner.protocol;

import eu.wohlben.qits.runner.protocol.health.CheckResult;
import eu.wohlben.qits.runner.protocol.health.HealthReport;
import java.util.List;

/**
 * Runner → qits-workspaces: the answer to {@link HealthCheck}. {@code ok} lifts a new runner's
 * quarantine and is true only when every check passed; {@code detail} says what was done, or what
 * failed. {@code checks} is the outcome of each named check, in the runner's order — extensible by
 * design: a new check is a new entry with its own {@code data}, never a new field.
 *
 * <p>{@code requestId} and {@code checks} were added after the frame was released, so they are
 * left off the wire when absent (null, empty) and read as absent from a peer that never sends
 * them: an original {@code healthChecked{ok, detail}} is still exactly that, both ways.
 *
 * <p>The shape is qits-runner-protocol's {@link HealthReport}, every runner kind's, and its wire is
 * {@link eu.wohlben.qits.runner.protocol.health.HealthWire}'s; this record is the workspace frame
 * that carries it, since a frame must sit in {@link WorkspacesRunnerMessage}'s sealed list.
 *
 * @param ok every check passed
 * @param detail a line for a person
 * @param requestId the {@link HealthCheck#requestId()} this answers, or null
 * @param checks each check's outcome; empty from a runner older than the field
 */
public record HealthChecked(boolean ok, String detail, String requestId, List<CheckResult> checks)
    implements WorkspacesRunnerMessage {

  public HealthChecked {
    checks = checks == null ? List.of() : List.copyOf(checks);
  }

  /** The original shape: no request id, no named checks. */
  public HealthChecked(boolean ok, String detail) {
    this(ok, detail, null, List.of());
  }

  /** The frame carrying {@code report}. */
  public static HealthChecked of(HealthReport report) {
    return new HealthChecked(report.ok(), report.detail(), report.requestId(), report.checks());
  }

  /** This frame's payload, as every runner kind reports it. */
  public HealthReport report() {
    return new HealthReport(ok, detail, requestId, checks);
  }
}
