package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.workspacesrunner.protocol.LoginPresence;
import eu.wohlben.qits.workspacesrunner.protocol.LoginState;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Whether the agent CLIs are logged in on this node — presence only. The probes run the workspace
 * image the runner already pulled against the node's agent home ({@link NodeVolumes#dotClaude}),
 * exactly as qits-coding-agents' {@code AgentAuthStatus} asks inside a workspace:
 *
 * <ul>
 *   <li>Claude Code: {@code claude auth status} with {@code HOME=/claude-home} and {@code
 *       CLAUDE_CONFIG_DIR=/claude-home/.claude} — the workspace container's own {@code
 *       CLAUDE_CONFIG_DIR} plus the {@code HOME} {@code AgentAuthStatus} adds — read by {@code
 *       loggedIn} in the JSON it prints. A probe with {@code CLAUDE_CONFIG_DIR=/claude-home} (what
 *       shipped first, qits-945) read a login one directory above where every workspace looks, and
 *       answered PRESENT for a node on which every launch was {@code not-signed-in};
 *   <li>Kimi Code: the credential file's presence under {@code /claude-home/.kimi-code} — either
 *       layout {@code AgentAuthStatus} accepts, the flat {@code credentials} file or {@code
 *       credentials/kimi-code.json}.
 * </ul>
 *
 * <p><b>Nothing a probe prints is logged, kept or sent</b> beyond the one presence each: {@code auth
 * status} names the account, and the platform never sees or moves a login. A probe that could not
 * run — no image here yet, no agent home, docker refusing, the {@link #DEADLINE} passing — is
 * {@link LoginPresence#UNKNOWN}, never ABSENT: an operator told "not logged in" would log in again
 * to fix a docker fault.
 *
 * <p>Each probe container is {@code --rm} and carries the runner's ownership label, so one that
 * outlives its deadline still goes by itself — and, at the latest, with the runner at decommission.
 */
final class LoginProbe {

  private static final Logger LOG = Logger.getLogger(LoginProbe.class);

  /** Each probe's: a {@code claude auth status} that has not answered by then is not answering. */
  static final Duration DEADLINE = Duration.ofSeconds(30);

  /** Where the agent home is mounted, as every workspace container mounts it. */
  static final String CLAUDE_HOME = "/claude-home";

  /**
   * Claude Code's config directory inside the agent home ({@code CLAUDE_CONFIG_DIR}), as
   * qits-workspaces sets it on every workspace container: {@code <claudeMount>/.claude}.
   */
  static final String CLAUDE_CONFIG_DIR = CLAUDE_HOME + "/.claude";

  /** Kimi's home inside the agent home ({@code KIMI_CODE_HOME}), as qits-workspaces sets it. */
  static final String KIMI_HOME = CLAUDE_HOME + "/.kimi-code";

  /** {@code AgentAuthStatus}'s Kimi check, word for word: either credential layout. */
  static final String KIMI_CHECK =
      "test -f \"$KIMI_CODE_HOME/credentials\" "
          + "|| test -f \"$KIMI_CODE_HOME/credentials/kimi-code.json\"";

  private final RunnerIdentity identity;
  private final String runnerId;
  private final RunnerDocker docker;
  private final NodeVolumes volumes;
  private final Clock clock;

  /**
   * @param docker the runner's docker under the probe's own {@link #DEADLINE}
   */
  LoginProbe(
      RunnerIdentity identity,
      String runnerId,
      RunnerDocker docker,
      NodeVolumes volumes,
      Clock clock) {
    this.identity = identity;
    this.runnerId = runnerId;
    this.docker = docker;
    this.volumes = volumes;
    this.clock = clock;
  }

  /** Both probes against {@code image} (null before the first {@code estate}); never throws. */
  synchronized LoginState probe(ContainersDriver driver, String image) {
    String checkedAt = clock.instant().toString();
    if (image == null || image.isBlank() || !NodeImages.present(docker, image)) {
      LOG.infof("workspaces-runner's login probe cannot run: the workspace image is not here yet");
      return new LoginState(LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, checkedAt);
    }
    ContainersDriver.OpResult home = volumes.ensureDotClaude(driver, docker.deadline());
    if (!home.ok()) {
      LOG.warnf("workspaces-runner's login probe cannot run: no agent home volume");
      return new LoginState(LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, checkedAt);
    }
    LoginPresence claude = claude(docker.run(claudeArgv(image)));
    LoginPresence kimi = kimi(docker.run(kimiArgv(image)));
    LOG.infof("workspaces-runner's login probe: claude %s, kimi %s", claude, kimi);
    return new LoginState(claude, kimi, checkedAt);
  }

  /**
   * {@code docker run --rm --label <owner> --user 1000 --entrypoint claude -v
   * <dot-claude>:/claude-home -e HOME=/claude-home -e CLAUDE_CONFIG_DIR=/claude-home/.claude
   * <image> auth status}. The workspace user, not the image's default (root): a login written by this probe as
   * root would read PRESENT here and still be unreadable by the workspace that actually uses it.
   */
  List<String> claudeArgv(String image) {
    return List.of(
        docker.binary(),
        "run",
        "--rm",
        "--label",
        ownership(),
        "--user",
        WorkspaceLauncher.USER,
        "--entrypoint",
        "claude",
        "-v",
        volumes.dotClaude() + ":" + CLAUDE_HOME,
        "-e",
        "HOME=" + CLAUDE_HOME,
        "-e",
        "CLAUDE_CONFIG_DIR=" + CLAUDE_CONFIG_DIR,
        LifecycleArgv.requireImage(image),
        "auth",
        "status");
  }

  /**
   * {@code docker run --rm --label <owner> --user 1000 --entrypoint bash -v
   * <dot-claude>:/claude-home -e KIMI_CODE_HOME=/claude-home/.kimi-code <image> -c <KIMI_CHECK>}.
   * The workspace user, for the same reason {@link #claudeArgv} is.
   */
  List<String> kimiArgv(String image) {
    return List.of(
        docker.binary(),
        "run",
        "--rm",
        "--label",
        ownership(),
        "--user",
        WorkspaceLauncher.USER,
        "--entrypoint",
        "bash",
        "-v",
        volumes.dotClaude() + ":" + CLAUDE_HOME,
        "-e",
        "KIMI_CODE_HOME=" + KIMI_HOME,
        LifecycleArgv.requireImage(image),
        "-c",
        KIMI_CHECK);
  }

  /**
   * {@code AgentAuthStatus.parseClaudeLoggedIn}'s reading, with the one case it does not have: a
   * run that never reached the CLI is UNKNOWN. The JSON's {@code loggedIn} wins whatever the exit
   * code (a signed-out CLI exits non-zero and still prints it); without it, exit 0 is a login, a
   * run docker could not make (125), whose command could not start (126, 127) or that timed out is
   * UNKNOWN, and any other non-zero exit is the CLI saying no.
   */
  static LoginPresence claude(RunnerDocker.Result result) {
    String out = result.stdout() == null ? "" : result.stdout();
    if (!result.timedOut() && out.contains("\"loggedIn\"")) {
      return out.replace(" ", "").contains("\"loggedIn\":true")
          ? LoginPresence.PRESENT
          : LoginPresence.ABSENT;
    }
    return byExit(result);
  }

  /** The Kimi check: exit 0 present, 1 absent, anything that was not the check running UNKNOWN. */
  static LoginPresence kimi(RunnerDocker.Result result) {
    return byExit(result);
  }

  private static LoginPresence byExit(RunnerDocker.Result result) {
    if (result.timedOut()) {
      return LoginPresence.UNKNOWN;
    }
    return switch (result.exitCode()) {
      case 0 -> LoginPresence.PRESENT;
      case -1, 125, 126, 127 -> LoginPresence.UNKNOWN;
      default -> LoginPresence.ABSENT;
    };
  }

  private String ownership() {
    return identity.ownerLabel() + "=" + LifecycleArgv.requireRunnerId(runnerId);
  }
}
