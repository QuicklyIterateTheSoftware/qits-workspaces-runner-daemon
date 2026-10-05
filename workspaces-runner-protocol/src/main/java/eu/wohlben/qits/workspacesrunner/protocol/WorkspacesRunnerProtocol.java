package eu.wohlben.qits.workspacesrunner.protocol;

import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import java.util.Set;

/**
 * The single source of truth for the workspaces runner's control socket: its address, its
 * capability version, its own frames' tags and field names, and the roots every name the runner
 * uses is derived from.
 *
 * <p>The lifecycle frames — {@code hello}, {@code ack}, {@code heartbeat}, {@code backlog}, {@code
 * reserve}, {@code nothing}, {@code upgrade}, {@code retire}, {@code quarantined} and {@code
 * reinstated} — are qits-runner-protocol's and byte-identical to CI's; none is declared here. This
 * class holds the workspace frames, which travel through {@link WorkspacesRunnerCodec}:
 *
 * <pre>
 *   Hello{heldWorkspaces} → Ack{slots, adoptedWorkspaces} → Estate{owned, workspaceImage}
 *                         ← Inventory{containers, volumes, dotClaudeVolume}
 *   Reserve → Take{rowId, spec} | Nothing   → Launched{rowId, containerId} | LaunchFailed{rowId, detail}
 *   Stop{rowId} → Stopped{rowId}            Delete{rowId} → Deleted{rowId}
 *   Exited{rowId, exitCode}                 (a workspace container that stopped by itself)
 *   ProbeLogin → LoginState{claude, kimi, checkedAt}
 *   HealthCheck → HealthChecked{ok, detail}
 * </pre>
 *
 * <p><b>Identity is not on the wire.</b> The runner presents a bearer minted from the idp client it
 * registered; qits-workspaces knows which runner this is before the first frame is read.
 *
 * <p><b>A row id is a JSON number</b> — qits-workspaces' {@code workspace.id}, a {@code bigint}.
 * The lifecycle frames' held and adopted lists ({@code heldWorkspaces}, {@code adoptedWorkspaces})
 * carry the same ids as strings, because the base's lists are lists of strings for every runner
 * kind; {@code Long.toString(rowId)} is the one spelling both sides use there.
 */
public final class WorkspacesRunnerProtocol {

  /**
   * The capability version the runner announces in its {@code Hello} and qits-workspaces echoes in
   * its {@code Ack}. Bumped only when either side must branch on a change: a new frame or a new
   * field is not a bump, because both codecs drop an unknown type and ignore an unknown field.
   */
  public static final int CAPABILITY_VERSION = 1;

  /** The control socket's path below the service's public url: {@code wss://workspaces.qits.<d>…}. */
  public static final String SOCKET_PATH = "/workspaces/runners/socket";

  /**
   * The workspaces runner's names for the two lists the base carries across a lost socket: the
   * running workspace containers it holds ({@code Hello}) and the ones the host kept ({@code Ack}).
   */
  public static final RunnerVocabulary VOCABULARY =
      new RunnerVocabulary("heldWorkspaces", "adoptedWorkspaces");

  // --- the identity roots ------------------------------------------------------------------------
  //
  // qits-runner-toolkit's RunnerIdentity is built from exactly these, by the runner (qits-842) and by
  // qits-workspaces-service for its install script (qits-848). They are java.* constants rather than
  // an identity because RunnerIdentity lives in the toolkit, which this jar must not depend on; both
  // embedders pass them in the same order, with WorkspacesRunnerBinary.VERSION, CAPABILITY_VERSION
  // and VOCABULARY. The toolkit's KnownRunnerNamespaces.WORKSPACES spells the first four too, and the
  // runner module's WorkspacesRunnerIdentityTest holds them equal.

  /** {@code RunnerIdentity.kind}. */
  public static final String KIND = "workspaces";

  /** {@code RunnerIdentity.envPrefix}: every variable is {@code QITS_WORKSPACES_RUNNER_<NAME>}. */
  public static final String ENV_PREFIX = "QITS_WORKSPACES_RUNNER_";

  /** {@code RunnerIdentity.labelRoot}: {@code qits.workspaces.runner=<runnerId>} and below it. */
  public static final String LABEL_ROOT = "qits.workspaces.runner";

  /** {@code RunnerIdentity.namePrefix}: every container and volume the runner names. */
  public static final String NAME_PREFIX = "qits-workspaces-runner";

  /** {@code RunnerIdentity.imageRepository}, below the registry host and without a tag. */
  public static final String IMAGE_REPOSITORY = "qits/qits-workspaces-runner";

  /** {@code RunnerIdentity.defaultStateDir}: where the state volume is mounted. */
  public static final String STATE_DIR = "/var/lib/qits-workspaces-runner";

  /** {@code RunnerIdentity.registerPath}: the register door, {@code {id}} the runner id. */
  public static final String REGISTER_PATH = "/workspaces/api/runners/{id}/register";

  /** {@code RunnerIdentity.serviceName}: the runner's own log lands in {@code _service/<this>}. */
  public static final String SERVICE_NAME = "qits-workspaces-runner";

  /** {@code RunnerIdentity.workLabelSuffix}: {@code qits.workspaces.runner.workspace=<rowId>}. */
  public static final String WORK_LABEL_SUFFIX = "workspace";

  private WorkspacesRunnerProtocol() {}

  /** The {@code "type"} discriminator values of the workspace frames. */
  public static final class Type {
    // qits-workspaces -> runner
    public static final String ESTATE = "estate";
    public static final String TAKE = "take";
    public static final String STOP = "stop";
    public static final String DELETE = "delete";
    public static final String PROBE_LOGIN = "probeLogin";
    public static final String HEALTH_CHECK = "healthCheck";
    // runner -> qits-workspaces
    public static final String INVENTORY = "inventory";
    public static final String LAUNCHED = "launched";
    public static final String LAUNCH_FAILED = "launchFailed";
    public static final String STOPPED = "stopped";
    public static final String DELETED = "deleted";
    public static final String EXITED = "exited";
    public static final String LOGIN_STATE = "loginState";
    public static final String HEALTH_CHECKED = "healthChecked";

    /** Every workspace type — what {@link WorkspacesRunnerCodec#types()} answers. */
    public static final Set<String> ALL =
        Set.of(
            ESTATE,
            TAKE,
            STOP,
            DELETE,
            PROBE_LOGIN,
            HEALTH_CHECK,
            INVENTORY,
            LAUNCHED,
            LAUNCH_FAILED,
            STOPPED,
            DELETED,
            EXITED,
            LOGIN_STATE,
            HEALTH_CHECKED);

    private Type() {}
  }

  /** The field names of the workspace frames and of the objects nested in them. */
  public static final class Field {
    public static final String TYPE = "type";
    public static final String ROW_ID = "rowId";
    public static final String OWNED = "owned";
    public static final String WORKSPACE_IMAGE = "workspaceImage";
    public static final String SPEC = "spec";
    public static final String CONTAINERS = "containers";
    public static final String VOLUMES = "volumes";
    public static final String DOT_CLAUDE_VOLUME = "dotClaudeVolume";
    public static final String RUNNING = "running";
    public static final String SPEC_HASH = "specHash";
    public static final String CONTAINER_ID = "containerId";
    public static final String DETAIL = "detail";
    public static final String EXIT_CODE = "exitCode";
    public static final String CLAUDE = "claude";
    public static final String KIMI = "kimi";
    public static final String CHECKED_AT = "checkedAt";
    public static final String OK = "ok";
    // WorkspaceSpec
    public static final String IMAGE = "image";
    public static final String ENV = "env";
    public static final String MOUNTS = "mounts";
    public static final String LABELS = "labels";
    public static final String MEMORY_LIMIT = "memoryLimit";
    public static final String MEMORY_SWAP_LIMIT = "memorySwapLimit";
    public static final String PIDS_LIMIT = "pidsLimit";
    public static final String CPUS = "cpus";
    public static final String OOM_SCORE_ADJ = "oomScoreAdj";
    public static final String INIT = "init";
    // Mount
    public static final String VOLUME = "volume";
    public static final String TARGET = "target";

    private Field() {}
  }
}
