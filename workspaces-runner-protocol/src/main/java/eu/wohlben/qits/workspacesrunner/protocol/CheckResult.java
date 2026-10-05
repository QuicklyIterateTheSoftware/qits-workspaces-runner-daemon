package eu.wohlben.qits.workspacesrunner.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One named check's outcome in a {@link HealthChecked}.
 *
 * <p>{@code data} is a JSON object whose shape is the check's own — strings, numbers, booleans,
 * null, and lists and objects of those — and the reason a check can be added without a protocol
 * change. Kept in its insertion order and allowed to hold null values, which is why it is not a
 * {@code Map.copyOf}.
 *
 * @param name the check's stable name ({@code docker}, {@code selfTest}, {@code nodeInventory}, …)
 * @param ok whether the check found what it looks for
 * @param detail one line for a person: what was found, or what went wrong
 * @param data the check's facts; empty, never null
 */
public record CheckResult(String name, boolean ok, String detail, Map<String, Object> data) {

  public CheckResult {
    data = Collections.unmodifiableMap(new LinkedHashMap<>(data == null ? Map.of() : data));
  }
}
