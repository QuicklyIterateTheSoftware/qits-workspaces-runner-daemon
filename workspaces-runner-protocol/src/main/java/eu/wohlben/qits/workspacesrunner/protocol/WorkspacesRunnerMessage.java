package eu.wohlben.qits.workspacesrunner.protocol;

import eu.wohlben.qits.runner.protocol.RunnerMessage;

/**
 * Every workspace frame on the runner's control socket. Sealed here and extending the base's
 * non-sealed {@link RunnerMessage}, because a {@code permits} list cannot span artifacts: the
 * lifecycle frames are qits-runner-protocol's, and {@code RunnerCodec} hands everything else to
 * {@link WorkspacesRunnerCodec}. Each side switches exhaustively over this set for its own frames.
 */
public sealed interface WorkspacesRunnerMessage extends RunnerMessage
    permits Estate,
        Take,
        Stop,
        Delete,
        ProbeLogin,
        HealthCheck,
        Inventory,
        Launched,
        LaunchFailed,
        Stopped,
        Deleted,
        Exited,
        LoginState,
        HealthChecked {}
