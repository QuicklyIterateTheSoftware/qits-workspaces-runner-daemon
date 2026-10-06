package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.runner.toolkit.Heartbeat;
import eu.wohlben.qits.runner.toolkit.RunnerLauncher;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.util.function.Function;

/**
 * Entry point, and <b>the one CDI shell</b>: it hands {@link WorkspacesRunnerIdentity#IDENTITY},
 * the domain's constructor and the managed {@code Vertx} to qits-runner-toolkit's {@link
 * RunnerLauncher}, which parses the environment ({@code RunnerSettings}, exit 2 when it is
 * missing), registers, connects and runs. Nothing below reads configuration itself.
 *
 * <p><b>Three answers come before Quarkus starts</b>, as they do in qits-ci-runner-daemon's {@code
 * Main}: the {@code health} subcommand (the container healthcheck: one file's mtime), {@code
 * QITS_WORKSPACES_RUNNER_PRINT_VERSION} (the image's smoke probe, whose stdout must be the version
 * and nothing else — no startup line beside it) and {@code QITS_WORKSPACES_RUNNER_DECOMMISSION}
 * (the decommission helper). {@link RunnerLauncher} answers each of them without touching Vert.x,
 * so they are handed none; {@code MainTest} holds that true for the pinned toolkit.
 */
@QuarkusMain
public class Main {

  public static void main(String... args) {
    if (answersBeforeQuarkus(args, System::getenv)) {
      System.exit(
          RunnerLauncher.main(
              WorkspacesRunnerIdentity.IDENTITY, WorkspacesRunnerDomain::new, null, args));
    }
    Quarkus.run(RunnerApplication.class, args);
  }

  /**
   * Whether {@link RunnerLauncher} answers this invocation from its arguments or one variable
   * alone, in the order it reads them: {@code health}, the version probe, helper mode.
   */
  static boolean answersBeforeQuarkus(String[] args, Function<String, String> env) {
    if (args != null && args.length > 0 && Heartbeat.ARG.equals(args[0])) {
      return true;
    }
    var identity = WorkspacesRunnerIdentity.IDENTITY;
    if (RunnerSettings.printsVersion(env.apply(identity.env(RunnerSettings.PRINT_VERSION)))) {
      return true;
    }
    String decommission = env.apply(identity.env(RunnerLauncher.DECOMMISSION));
    return decommission != null && !decommission.isBlank();
  }

  /** The runner itself, on the Vert.x Quarkus manages. */
  public static class RunnerApplication implements QuarkusApplication {

    @Inject Vertx vertx;

    @Override
    public int run(String... args) {
      return RunnerLauncher.main(
          WorkspacesRunnerIdentity.IDENTITY, WorkspacesRunnerDomain::new, vertx, args);
    }
  }
}
