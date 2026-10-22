package eu.wohlben.qits.workspacesrunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * <b>The runner's consumer message pact against qits-workspaces-service</b> (qits-950, A1): every
 * row of {@link ServiceFramesContract}, its frame handed to the real runner and the effect asserted
 * — one invocation per row, so each fails on its own and by name — and the committed {@code
 * pacts/qits-workspaces-runner-daemon_qits-workspaces-service.json} they make, compared by default
 * and rewritten under {@code -Dgolden.update=true}, every interaction carrying both references.
 *
 * <p>The platform publishes the file in the existing {@code
 * eu.wohlben.qits:qits-workspaces-runner-daemon-pacts-qits-workspaces-service}, beside the
 * toolkit's register pact: that package packs every {@code pacts/*_qits-workspaces-service.json}.
 */
@EnabledOnOs(OS.LINUX)
class ServiceFramesConsumerPactTest {

  @TempDir Path work;

  static Stream<Arguments> rows() {
    return ServiceFramesContract.CONTRACT.rows().stream()
        .map(row -> Arguments.of(row.description(), row.state()));
  }

  @ParameterizedTest(name = "{0} [{1}]")
  @MethodSource("rows")
  void theRunnerHandlesTheFrameAsTheContractSays(String description, String state)
      throws Exception {
    var contract = ServiceFramesContract.CONTRACT;
    contract.receive(
        contract.row(description, state), Files.createDirectories(work.resolve("node")));
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    ServiceFramesContract.CONTRACT.compareOrWritePactFile();
  }

  @Test
  void everyInteractionCarriesBothReferences() {
    ServiceFramesContract.CONTRACT.assertEveryInteractionCarriesBothReferences();
  }
}
