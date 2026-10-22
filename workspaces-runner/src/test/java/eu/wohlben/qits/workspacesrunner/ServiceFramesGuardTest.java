package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.LifecycleMessage;
import eu.wohlben.qits.runner.toolkit.RunnerRuntime;
import eu.wohlben.qits.workspacesrunner.contracts.MessageContract.Row;
import eu.wohlben.qits.workspacesrunner.contracts.MessageGoldenMasters.Recorded;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerMessage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * <b>No frame qits-workspaces-service sends the runner goes without a contract row</b> (qits-950
 * A3, the receiving side): the frames are read off the code that dispatches them, never off a list
 * in this test, so a frame the runner starts handling fails here by name before the service can
 * change it unpacted.
 *
 * <p><b>What the runner handles</b> is what its dispatch switches over. Both are pattern-matching
 * {@code switch}es, which javac compiles to one {@code invokedynamic} of {@code
 * SwitchBootstraps.typeSwitch} whose static arguments are the case classes: {@link
 * WorkspacesRunnerDomain}'s for its own frames (estate, take, stop, …) and qits-runner-toolkit's
 * {@link RunnerRuntime}'s for the lifecycle frames (ack, backlog, upgrade, …), which never reach
 * the domain. A case class that is a permitted subclass of {@link LifecycleMessage} or {@link
 * WorkspacesRunnerMessage} is a frame handled; the {@code default} arm, which only logs, is not.
 * Read with the JDK's {@code java.lang.classfile}, so what is checked is what ships.
 *
 * <p><b>What a row receives</b> is its recorded frame decoded by the runner's own codec — the class
 * the service's bytes actually are, whatever the row calls it.
 */
class ServiceFramesGuardTest {

  private static final String TYPE_SWITCH = "typeSwitch";
  private static final ClassDesc SWITCH_BOOTSTRAPS =
      ClassDesc.of("java.lang.runtime.SwitchBootstraps");

  @Test
  void everyFrameTheRunnerHandlesHasAContractRow() throws Exception {
    Map<Class<?>, Set<String>> handled = handledFrames();
    Map<Class<?>, List<String>> rows = rowFrames();
    List<String> missing = new ArrayList<>();
    handled.forEach(
        (frame, where) -> {
          if (!rows.containsKey(frame)) {
            missing.add(
                "  "
                    + RunnerFramesGuardTest.wireName(frame)
                    + " ("
                    + frame.getName()
                    + "), dispatched in "
                    + where);
          }
        });
    assertTrue(
        missing.isEmpty(),
        () ->
            "The runner handles frames of qits-workspaces-service's that no row of"
                + " ServiceFramesContract receives:\n"
                + String.join("\n", missing)
                + "\nAdd a row for each — the service must record the frame in a state first —"
                + " and rerun ServiceFramesConsumerPactTest with -Dgolden.update=true.");
  }

  @Test
  void everyFrameTheServiceRecordsIsReceivedByARow() {
    Set<String> covered = new HashSet<>();
    ServiceFramesContract.CONTRACT
        .rows()
        .forEach(r -> covered.add(r.state() + "\u0000" + r.frame()));
    List<String> unreceived = new ArrayList<>();
    for (Recorded recorded : ServiceFramesContract.MASTERS.frames()) {
      if (!covered.contains(recorded.state() + "\u0000" + recorded.frame())) {
        unreceived.add(
            "  " + recorded.frame() + " in '" + recorded.state() + "' (" + recorded.file() + ")");
      }
    }
    assertTrue(
        unreceived.isEmpty(),
        () ->
            "qits-workspaces-service's pinned golden masters record frames no row of"
                + " ServiceFramesContract receives — the runner's pact would not hold them:\n"
                + String.join("\n", unreceived));
  }

  /** A scan that finds nothing would pass the first test vacuously; it must see both sets. */
  @Test
  void theScanSeesTheToolkitsDispatchAndThisRunnersOwn() throws Exception {
    Set<Class<?>> handled = handledFrames().keySet();
    assertTrue(
        handled.stream().anyMatch(LifecycleMessage.class::isAssignableFrom),
        () -> "no lifecycle frame found dispatched in qits-runner-toolkit: " + handled);
    assertTrue(
        handled.stream().anyMatch(WorkspacesRunnerMessage.class::isAssignableFrom),
        () -> "no workspace frame found dispatched in this module: " + handled);
  }

  // --- the code's side ---------------------------------------------------------------------------

  /** Every frame a {@code typeSwitch} of the two dispatching classes has a case for, and where. */
  static Map<Class<?>, Set<String>> handledFrames() throws IOException {
    Map<String, Class<?>> candidates = new HashMap<>();
    for (Class<?> set : List.of(LifecycleMessage.class, WorkspacesRunnerMessage.class)) {
      for (Class<?> frame : set.getPermittedSubclasses()) {
        candidates.put(frame.descriptorString(), frame);
      }
    }
    Map<Class<?>, Set<String>> handled =
        new TreeMap<>((a, b) -> a.getName().compareTo(b.getName()));
    for (Class<?> dispatcher : List.of(WorkspacesRunnerDomain.class, RunnerRuntime.class)) {
      ClassModel model = ClassFile.of().parse(bytes(dispatcher));
      for (MethodModel method : model.methods()) {
        method
            .code()
            .ifPresent(
                code ->
                    code.forEach(
                        element -> {
                          if (element instanceof InvokeDynamicInstruction indy
                              && indy.bootstrapMethod().owner().equals(SWITCH_BOOTSTRAPS)
                              && indy.bootstrapMethod().methodName().equals(TYPE_SWITCH)) {
                            for (var arg : indy.bootstrapArgs()) {
                              if (arg instanceof ClassDesc label
                                  && candidates.containsKey(label.descriptorString())) {
                                handled
                                    .computeIfAbsent(
                                        candidates.get(label.descriptorString()),
                                        k -> new TreeSet<>())
                                    .add(
                                        dispatcher.getSimpleName()
                                            + "."
                                            + method.methodName().stringValue());
                              }
                            }
                          }
                        }));
      }
    }
    return handled;
  }

  private static byte[] bytes(Class<?> type) throws IOException {
    String resource = type.getName().replace('.', '/') + ".class";
    try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("cannot read the class file of " + type.getName());
      }
      return in.readAllBytes();
    }
  }

  // --- the rows' side ----------------------------------------------------------------------------

  /** Every frame class a row receives — its recording, decoded — with the rows receiving it. */
  static Map<Class<?>, List<String>> rowFrames() {
    Map<Class<?>, List<String>> out = new HashMap<>();
    for (Row row : ServiceFramesContract.CONTRACT.rows()) {
      Recorded recorded = ServiceFramesContract.CONTRACT.recorded(row);
      Class<?> type =
          RunnerProviderStates.CODEC
              .decode(ServiceFramesContract.MASTERS.json(recorded).getMap())
              .getClass();
      out.computeIfAbsent(type, k -> new ArrayList<>()).add(row.description());
    }
    return out;
  }
}
