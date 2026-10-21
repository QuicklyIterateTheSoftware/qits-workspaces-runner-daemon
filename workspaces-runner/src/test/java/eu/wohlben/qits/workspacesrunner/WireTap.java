package eu.wohlben.qits.workspacesrunner;

import eu.wohlben.qits.runner.toolkit.testing.FakeRunnerHost;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <b>The runner's frames exactly as it wrote them</b>: a WebSocket hop between the runner and
 * {@link FakeRunnerHost} that forwards every frame both ways and keeps the runner's text frames
 * verbatim, in order. The host's register door is told to hand out this hop's address as the socket
 * url, so the runner dials it unknowingly.
 *
 * <p>Why a hop rather than {@link FakeRunnerHost#received}: the host keeps frames <em>decoded</em>,
 * and a frame re-encoded from its record is the codec's opinion of the frame, not the bytes the
 * service reads — a field the runner writes and its own decoder drops would vanish from a golden
 * master built that way. Why not a handler added to the host's socket: the host installs its own
 * the moment the upgrade lands, and the {@code hello} is already on its way by the time a suite
 * could add a second.
 */
final class WireTap implements AutoCloseable {

  /** Every text frame the runner sent, as written, across every connection, in arrival order. */
  private final List<String> sent = Collections.synchronizedList(new ArrayList<>());

  private final HttpServer server;
  private final WebSocketClient client;

  /** A hop in front of {@code host}, and {@code host}'s register door pointing the runner at it. */
  WireTap(Vertx vertx, FakeRunnerHost host, String runnerId) throws Exception {
    this.client = vertx.createWebSocketClient();
    String upstream = host.base().replaceFirst("^http", "ws") + FakeRunnerHost.SOCKET_PATH;
    this.server =
        vertx
            .createHttpServer()
            .webSocketHandler(runnerSide -> relay(runnerSide, upstream))
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    host.registerBody =
        new JsonObject(host.clientJson(runnerId))
            .put("socketUrl", "ws://127.0.0.1:" + server.actualPort() + FakeRunnerHost.SOCKET_PATH)
            .encode();
  }

  private void relay(ServerWebSocket runnerSide, String upstream) {
    // Held until the host side is open, so not one frame is read before it can be forwarded.
    runnerSide.pause();
    WebSocketConnectOptions options = new WebSocketConnectOptions().setAbsoluteURI(upstream);
    String bearer = runnerSide.headers().get("Authorization");
    if (bearer != null) {
      options.addHeader("Authorization", bearer);
    }
    client
        .connect(options)
        .onSuccess(
            (WebSocket hostSide) -> {
              runnerSide.textMessageHandler(
                  json -> {
                    sent.add(json);
                    hostSide.writeTextMessage(json);
                  });
              hostSide.textMessageHandler(runnerSide::writeTextMessage);
              hostSide.closeHandler(
                  v -> {
                    if (!runnerSide.isClosed()) {
                      Short code = hostSide.closeStatusCode();
                      if (code == null) {
                        runnerSide.close();
                      } else {
                        runnerSide.close(code, hostSide.closeReason());
                      }
                    }
                  });
              runnerSide.closeHandler(
                  v -> {
                    if (!hostSide.isClosed()) {
                      hostSide.close();
                    }
                  });
              runnerSide.resume();
            })
        .onFailure(t -> runnerSide.close());
  }

  /** How many frames the runner has sent so far: a mark to await frames after. */
  int mark() {
    return sent.size();
  }

  /**
   * The {@code nth} (1-based) frame of {@code type} the runner sent at or after {@code from},
   * waiting up to twenty seconds for it.
   */
  String await(String type, int from, int nth) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      int seen = 0;
      synchronized (sent) {
        for (int i = from; i < sent.size(); i++) {
          if (type.equals(new JsonObject(sent.get(i)).getString("type")) && ++seen == nth) {
            return sent.get(i);
          }
        }
      }
      Thread.sleep(20);
    }
    synchronized (sent) {
      throw new AssertionError(
          "the runner never sent " + nth + " " + type + " frame(s) after #" + from + ": " + sent);
    }
  }

  @Override
  public void close() throws Exception {
    server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    client.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }
}
