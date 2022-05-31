package ru.yandex.cloud.ml.platform.lzy.kharon;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import yandex.cloud.priv.datasphere.v2.lzy.Servant;

import java.net.URI;
import java.net.URISyntaxException;

public class ServerController {
    private static final Logger LOG = LogManager.getLogger(ServerController.class);

    private StreamObserver<Servant.ServantProgress> progress;
    private final UriResolver uriResolver;
    private final String sessionId;

    public enum State {
        CREATED,
        CONNECTED,
        ERRORED,
        COMPLETED,
        DISCONNECTED
    }

    private State state;

    public ServerController(
        String sessionId,
        UriResolver uriResolver
    ) {
        this.uriResolver = uriResolver;
        this.sessionId = sessionId;
        this.state = State.CREATED;
    }

    public void setProgress(StreamObserver<Servant.ServantProgress> progress) throws ServerControllerResetException {
        if (state == State.CONNECTED) {
            throw new IllegalStateException("Server already connected with servant progress sessionId=" + sessionId);
        }
        if (isFinalState()) {
            throw new ServerControllerResetException();
        }
        this.progress = progress;
        updateState(State.CONNECTED);
    }

    private boolean isFinalState() {
        return state == State.ERRORED || state == State.COMPLETED || state == State.DISCONNECTED;
    }

    public void attach(Servant.SlotAttach attach) throws ServerControllerResetException {
        try {
            sendMessage(Servant.ServantProgress.newBuilder()
                .setAttach(Servant.SlotAttach.newBuilder()
                    .setSlot(attach.getSlot())
                    .setUri(uriResolver.appendWithSessionId(URI.create(attach.getUri()), sessionId).toString())
                    .setChannel(attach.getChannel())
                    .build())
                .build());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public void detach(Servant.SlotDetach detach) throws ServerControllerResetException {
        try {
            sendMessage(Servant.ServantProgress.newBuilder()
                .setDetach(Servant.SlotDetach.newBuilder()
                    .setSlot(detach.getSlot())
                    .setUri(uriResolver.appendWithSessionId(URI.create(detach.getUri()), sessionId).toString())
                    .build())
                .build());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void terminate(Throwable th) {
        LOG.info("Server connection sessionId={} terminated, throwable={}", sessionId, th);
        if (state == State.CONNECTED) {
            try {
                progress.onError(th);
            } catch (StatusRuntimeException e) {
                updateState(State.DISCONNECTED);
            }
        }
        updateState(State.ERRORED);
    }

    public synchronized void complete() {
        LOG.info("ServerController sessionId={} completed", sessionId);
        if (state == State.CONNECTED) {
            try {
                progress.onCompleted();
            } catch (StatusRuntimeException e) {
                updateState(State.DISCONNECTED);
            }
        }
        updateState(State.COMPLETED);
    }

    public State state() {
        return state;
    }

    public void onDisconnect() {
        updateState(State.DISCONNECTED);
    }

    private synchronized void updateState(State state) {
        if (isFinalState()) {
            LOG.warn(
                "ServerController sessionId={} attempt to change final state {} to {}",
                sessionId,
                this.state,
                state
            );
            return;
        }
        LOG.info("ServerController sessionId={} change state from {} to {}", sessionId, this.state, state);
        this.state = state;
        notifyAll();
    }

    private synchronized void sendMessage(Servant.ServantProgress message) throws ServerControllerResetException {
        while (State.CONNECTED != state && !isFinalState()) {
            try {
                wait();
            } catch (InterruptedException ignore) {
                // Ignored exception
            }
        }

        if (state != State.CONNECTED) {
            throw new ServerControllerResetException();
        }

        try {
            progress.onNext(message);
        } catch (StatusRuntimeException e) {
            updateState(State.DISCONNECTED);
        }
    }

    public static class ServerControllerResetException extends Exception { }
}
