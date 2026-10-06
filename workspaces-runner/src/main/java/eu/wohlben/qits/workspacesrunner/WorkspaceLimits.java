package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol.Env;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol.Field;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The memory a workspace container may use on this node, as the operator set it: <b>runner
 * configuration, like the slots</b>, because what a node can safely give a workspace depends on the
 * host. The runner only announces it, in its {@code Hello}; qits-workspaces stamps it into the
 * {@code WorkspaceSpec} it sends back, and {@link WorkspaceLauncher} applies the spec as it always
 * did. Applying the local value directly would let the spec and the container disagree.
 *
 * <p>Each value is kept as the operator spelled it (trimmed), or null when unset — and a null
 * value is not announced, so an unconfigured runner's {@code Hello} is byte-identical to one that
 * predates the variables.
 *
 * <p>The check is docker's own, lightly: a size docker's parser would refuse, and a swap total
 * below the memory cap, fail at startup with a sentence naming the variable, rather than as a
 * {@code launchFailed} on every take.
 */
record WorkspaceLimits(String memoryLimit, String memorySwapLimit) {

  /** An operator who set neither: nothing is announced. */
  static final WorkspaceLimits NONE = new WorkspaceLimits(null, null);

  /** docker's {@code units.RAMInBytes}: binary units, an optional {@code i} and {@code b}. */
  private static final Pattern SIZE =
      Pattern.compile("(\\d+(?:\\.\\d+)?) ?([kmgtp])?i?b?", Pattern.CASE_INSENSITIVE);

  /** {@code --memory-swap}'s spelling of "unlimited swap". */
  private static final String UNLIMITED = "-1";

  /**
   * Parse the two variables out of {@code env}, beside the toolkit's {@link RunnerSettings#parse}.
   * {@code null} and blank both mean unset.
   */
  static WorkspaceLimits parse(RunnerIdentity identity, Function<String, String> env)
      throws RunnerSettings.Invalid {
    String memoryVariable = identity.env(Env.WORKSPACE_MEMORY_LIMIT);
    String swapVariable = identity.env(Env.WORKSPACE_MEMORY_SWAP_LIMIT);
    String memory = trimmed(env.apply(memoryVariable));
    String swap = trimmed(env.apply(swapVariable));
    Long memoryBytes = memory == null ? null : bytes(memoryVariable, memory);
    if (swap != null && !swap.equals(UNLIMITED)) {
      long swapBytes = bytes(swapVariable, swap);
      if (memoryBytes != null && swapBytes < memoryBytes) {
        // The likeliest slip: swap given as the swap alone, where docker wants the total.
        throw new RunnerSettings.Invalid(
            swapVariable
                + " is memory plus swap, so it cannot be below "
                + memoryVariable
                + ": '"
                + swap
                + "' < '"
                + memory
                + "'");
      }
    }
    return new WorkspaceLimits(memory, swap);
  }

  /** The {@code Hello} keys, in this order, each only when it was set. */
  Map<String, Object> announced() {
    Map<String, Object> keys = new LinkedHashMap<>();
    if (memoryLimit != null) {
      keys.put(Field.WORKSPACE_MEMORY_LIMIT, memoryLimit);
    }
    if (memorySwapLimit != null) {
      keys.put(Field.WORKSPACE_MEMORY_SWAP_LIMIT, memorySwapLimit);
    }
    return keys;
  }

  private static long bytes(String variable, String value) throws RunnerSettings.Invalid {
    Matcher m = SIZE.matcher(value);
    if (!m.matches()) {
      throw new RunnerSettings.Invalid(
          variable + " is not a docker memory size such as 8g: '" + value + "'");
    }
    int power = m.group(2) == null ? 0 : "kmgtp".indexOf(m.group(2).toLowerCase(Locale.ROOT)) + 1;
    return (long) (Double.parseDouble(m.group(1)) * Math.pow(1024, power));
  }

  private static String trimmed(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
