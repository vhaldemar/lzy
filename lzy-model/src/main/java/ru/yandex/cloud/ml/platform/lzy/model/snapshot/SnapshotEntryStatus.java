package ru.yandex.cloud.ml.platform.lzy.model.snapshot;

import java.util.HashSet;
import java.util.Set;

public interface SnapshotEntryStatus {
    boolean empty();
    State status();
    SnapshotEntry entry();
    Set<String> dependentEntryIds();

    class Impl implements SnapshotEntryStatus {
        private final boolean empty;
        private final State status;
        private final SnapshotEntry entry;
        private final Set<String> deps;

        public Impl(boolean empty, State status, SnapshotEntry entry, Set<String> deps) {
            this.empty = empty;
            this.status = status;
            this.entry = entry;
            this.deps = new HashSet<>(deps);
        }

        public boolean empty() {
            return empty;
        }

        public State status() {
            return status;
        }

        public SnapshotEntry entry() {
            return entry;
        }

        public Set<String> dependentEntryIds() {
            return deps;
        }
    }

    // IN_PROGRESS --> started saving data
    // FINISHED --> finished saving data
    enum State {
        IN_PROGRESS,
        FINISHED
    }
}
