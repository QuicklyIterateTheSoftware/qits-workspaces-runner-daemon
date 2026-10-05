package eu.wohlben.qits.workspacesrunner.protocol;

/**
 * One workspace container in an {@link Inventory}.
 *
 * @param rowId the workspace row it belongs to
 * @param running whether docker reports it running
 * @param specHash the hash of the canonical spec it was created from, or null when it carries none
 */
public record HeldContainer(long rowId, boolean running, String specHash) {}
