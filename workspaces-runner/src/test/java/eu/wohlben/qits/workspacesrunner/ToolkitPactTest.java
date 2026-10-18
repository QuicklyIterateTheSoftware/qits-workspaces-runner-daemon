package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.runner.toolkit.contracts.RunnerToolkitPact;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The REST calls qits-runner-toolkit makes on this daemon's behalf, pacted as this daemon's own
 * consumer pacts (qits-1163): the token mint at qits-idp, the log export to qits-observability and
 * the register door at qits-workspaces-service, each through the real toolkit classes. Writes
 * {@code pacts/qits-workspaces-runner-daemon_qits-runner-javalib_<provider>.json} under {@code
 * -Dgolden.update=true}; a provider whose states are not recorded yet is skipped, naming them.
 */
class ToolkitPactTest {

  @TestFactory
  Stream<DynamicTest> theToolkitsCalls() {
    return RunnerToolkitPact.forConsumer("qits-workspaces-runner-daemon")
        .register(RunnerToolkitPact.WORKSPACES, "registerRunner", WorkspacesRunnerIdentity.IDENTITY)
        .token()
        .logExport()
        .tests();
  }
}
