package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;

/**
 * Who the workspaces runner is: the one value it hands qits-runner-toolkit besides its domain.
 * Every label, container, volume and variable name the runner uses is derived from it — {@code
 * qits.workspaces.runner=<id>}, {@code qits-workspaces-runner-<id8>-<version>}, {@code
 * QITS_WORKSPACES_RUNNER_URL}, … — and none is spelled anywhere else.
 *
 * <p>Built only from the protocol jar's constants, in {@link RunnerIdentity}'s order, so
 * qits-workspaces-service, which builds the same value for the install script it renders, cannot
 * name a different runner than this one is.
 */
public final class WorkspacesRunnerIdentity {

  /** The workspaces runner's identity, at the version this tree was built as. */
  public static final RunnerIdentity IDENTITY =
      new RunnerIdentity(
          WorkspacesRunnerProtocol.KIND,
          WorkspacesRunnerProtocol.ENV_PREFIX,
          WorkspacesRunnerProtocol.LABEL_ROOT,
          WorkspacesRunnerProtocol.NAME_PREFIX,
          WorkspacesRunnerProtocol.IMAGE_REPOSITORY,
          WorkspacesRunnerProtocol.STATE_DIR,
          WorkspacesRunnerProtocol.REGISTER_PATH,
          WorkspacesRunnerProtocol.SERVICE_NAME,
          WorkspacesRunnerBinary.VERSION,
          WorkspacesRunnerProtocol.CAPABILITY_VERSION,
          WorkspacesRunnerProtocol.VOCABULARY,
          WorkspacesRunnerProtocol.WORK_LABEL_SUFFIX);

  private WorkspacesRunnerIdentity() {}
}
