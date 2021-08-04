package ru.yandex.cloud.ml.platform.lzy.server.task;

import ru.yandex.cloud.ml.platform.lzy.model.Slot;
import ru.yandex.cloud.ml.platform.lzy.server.channel.Channel;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.UUID;

public interface SlotStatus {
    @Nullable
    UUID channelId();
    Task task();
    Slot slot();
    URI connected();

    long pointer();

    State state();

    enum State {
        PREPARING, UNBOUND, OPEN, CLOSED, SUSPENDED
    }
}
