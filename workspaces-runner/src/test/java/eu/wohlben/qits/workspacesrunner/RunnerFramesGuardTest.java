package eu.wohlben.qits.workspacesrunner;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.LifecycleMessage;
import eu.wohlben.qits.runner.toolkit.RunnerRuntime;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerMessage;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>No frame the runner sends goes without a golden master</b> (qits-950 A3, the recording side):
 * the frames are read off the code, never off a list in this test, so a new one fails here by name
 * before it reaches qits-workspaces-service unpinned.
 *
 * <p><b>What the runner sends</b> is what it constructs. The candidates are the two sealed frame
 * sets, qits-runner-protocol's {@link LifecycleMessage} and this repository's {@link
 * WorkspacesRunnerMessage}; a candidate is <em>sent</em> when the runner's own classes — this
 * module's main classes and qits-runner-toolkit's, its fakes under {@code testing/} aside — call
 * its constructor or a static factory answering it ({@code HealthChecked.of}). A received frame is
 * only ever taken apart (its accessors), and the one place that builds every frame, each codec's
 * decode arm, lives in the protocol jars, which are not scanned. Read from the bytecode with the
 * JDK's {@code java.lang.classfile}, so what is checked is what ships.
 *
 * <p><b>What is recorded</b> is {@code golden-masters/index.json}'s message operations, each file
 * decoded through the runner's codec: the classes the committed frames actually are, whatever their
 * entries claim.
 */
class RunnerFramesGuardTest {

  private static final String TOOLKIT_FAKES = "eu/wohlben/qits/runner/toolkit/testing/";

  @Test
  void everyFrameTheRunnerSendsHasAGoldenMaster() throws Exception {
    Map<Class<?>, Set<String>> sent = sentFrames();
    Map<Class<?>, String> recorded = recordedFrames();

    List<String> missing = new ArrayList<>();
    sent.forEach(
        (frame, where) -> {
          if (!recorded.containsKey(frame)) {
            missing.add("  " + wireName(frame) + " (" + frame.getName() + "), built in " + where);
          }
        });
    assertTrue(
        missing.isEmpty(),
        () ->
            "The runner sends frames golden-masters/index.json records in no state:\n"
                + String.join("\n", missing)
                + "\nAdd a state and the frame to RunnerProviderStates.FRAMES, rerun"
                + " RunnerGoldenMasterRecordingTest with -Dgolden.update=true, and commit what it"
                + " wrote.");
  }

  @Test
  void everyRecordedFrameIsOneTheRunnerSends() throws Exception {
    Map<Class<?>, Set<String>> sent = sentFrames();
    List<String> stray = new ArrayList<>();
    recordedFrames()
        .forEach(
            (frame, type) -> {
              if (!sent.containsKey(frame)) {
                stray.add("  " + type + " (" + frame.getName() + ")");
              }
            });
    assertTrue(
        stray.isEmpty(),
        () ->
            "golden-masters/ records frames the runner no longer builds — a consumer would pact"
                + " a frame nothing sends:\n"
                + String.join("\n", stray));
  }

  /** A scan that finds nothing would pass the guard vacuously; it must see both frame sets. */
  @Test
  void theScanSeesTheToolkitsFramesAndThisRunnersOwn() throws Exception {
    Set<Class<?>> sent = sentFrames().keySet();
    assertTrue(
        sent.stream().anyMatch(LifecycleMessage.class::isAssignableFrom),
        () -> "no lifecycle frame found built in qits-runner-toolkit: " + sent);
    assertTrue(
        sent.stream().anyMatch(WorkspacesRunnerMessage.class::isAssignableFrom),
        () -> "no workspace frame found built in this module: " + sent);
  }

  // --- the code's side ---------------------------------------------------------------------------

  /** Every frame the runner's classes build, with the classes that build it. */
  static Map<Class<?>, Set<String>> sentFrames() throws IOException, URISyntaxException {
    Map<String, Class<?>> candidates = new HashMap<>();
    for (Class<?> set : List.of(LifecycleMessage.class, WorkspacesRunnerMessage.class)) {
      for (Class<?> frame : set.getPermittedSubclasses()) {
        candidates.put(frame.getName().replace('.', '/'), frame);
      }
    }
    Map<Class<?>, Set<String>> sent = new TreeMap<>((a, b) -> a.getName().compareTo(b.getName()));
    scan(location(WorkspacesRunnerDomain.class), candidates, sent);
    scan(location(RunnerRuntime.class), candidates, sent);
    return sent;
  }

  private static Path location(Class<?> type) throws URISyntaxException {
    return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
  }

  /** A classes directory or a jar, every class in it but the toolkit's fakes. */
  private static void scan(
      Path location, Map<String, Class<?>> candidates, Map<Class<?>, Set<String>> sent)
      throws IOException {
    if (Files.isDirectory(location)) {
      scanTree(location, candidates, sent);
      return;
    }
    try (FileSystem jar = FileSystems.newFileSystem(location)) {
      scanTree(jar.getPath("/"), candidates, sent);
    }
  }

  private static void scanTree(
      Path root, Map<String, Class<?>> candidates, Map<Class<?>, Set<String>> sent)
      throws IOException {
    List<Path> classes;
    try (Stream<Path> walk = Files.walk(root)) {
      classes =
          walk.filter(p -> p.toString().endsWith(".class"))
              .filter(p -> !root.relativize(p).toString().startsWith(TOOLKIT_FAKES))
              .toList();
    }
    for (Path file : classes) {
      ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
      for (PoolEntry entry : model.constantPool()) {
        if (!(entry instanceof MethodRefEntry ref)) {
          continue;
        }
        Class<?> frame = candidates.get(ref.owner().asInternalName());
        if (frame == null) {
          continue;
        }
        boolean builds =
            ref.name().equalsString("<init>")
                || ref.typeSymbol().returnType().equals(ref.owner().asSymbol());
        if (builds) {
          sent.computeIfAbsent(frame, k -> new TreeSet<>())
              .add(model.thisClass().asInternalName().replace('/', '.'));
        }
      }
    }
  }

  // --- the recording's side ----------------------------------------------------------------------

  /** Every frame {@code golden-masters/} records, as the codec decodes its file, with its type. */
  static Map<Class<?>, String> recordedFrames() throws IOException {
    Path dir = GoldenFrames.repositoryRoot().resolve("golden-masters");
    Path indexFile = dir.resolve("index.json");
    Map<Class<?>, String> recorded = new HashMap<>();
    if (!Files.isRegularFile(indexFile)) {
      return recorded;
    }
    JsonObject index = new JsonObject(Files.readString(indexFile));
    for (Object state : index.getJsonArray("states", new JsonArray())) {
      for (Object operation : ((JsonObject) state).getJsonArray("operations", new JsonArray())) {
        JsonObject op = (JsonObject) operation;
        if (!RunnerGoldenMasterRecordingTest.MESSAGE.equals(op.getString("kind"))) {
          continue;
        }
        JsonObject frame = new JsonObject(Files.readString(dir.resolve(op.getString("file"))));
        Class<?> type = RunnerProviderStates.CODEC.decode(frame.getMap()).getClass();
        recorded.put(type, frame.getString("type"));
      }
    }
    return recorded;
  }

  /**
   * The wire name a frame class most likely carries — both protocols spell a type as its record's
   * name with the first letter lowered ({@code LaunchFailed} is {@code launchFailed}) — for naming
   * a frame that has no recording to read its real type from.
   */
  static String wireName(Class<?> frame) {
    String name = frame.getSimpleName();
    return name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
  }
}
