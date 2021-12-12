package ru.yandex.cloud.ml.platform.lzy.whiteboard.mem;

import io.grpc.Status;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.hibernate.Session;
import org.hibernate.Transaction;
import ru.yandex.cloud.ml.platform.lzy.model.snapshot.*;
import ru.yandex.cloud.ml.platform.lzy.whiteboard.SnapshotRepository;
import ru.yandex.cloud.ml.platform.lzy.whiteboard.exception.SnapshotException;
import ru.yandex.cloud.ml.platform.lzy.whiteboard.hibernate.DbStorage;
import ru.yandex.cloud.ml.platform.lzy.whiteboard.hibernate.models.*;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Singleton
@Requires(beans = DbStorage.class)
public class SnapshotRepositoryImpl implements SnapshotRepository {
    @Inject
    DbStorage storage;

    @Override
    public void create(Snapshot snapshot) throws SnapshotException {
        try (Session session = storage.getSessionFactory().openSession()){
            Transaction tx = session.beginTransaction();
            String snapshotId = snapshot.id().toString();
            SnapshotModel snapshotStatus = new SnapshotModel(snapshotId, SnapshotStatus.State.CREATED);
            SnapshotOwnerModel snapshotOwner = new SnapshotOwnerModel(snapshotId, snapshot.ownerId().toString());
            try {
                session.save(snapshotStatus);
                session.save(snapshotOwner);
                tx.commit();
            }
            catch (Exception e) {
                tx.rollback();
                throw new SnapshotException(e);
            }
        }
    }

    @Nullable
    @Override
    public SnapshotStatus resolveSnapshot(URI id) {
        try (Session session = storage.getSessionFactory().openSession()){
            SnapshotModel snapshotModel = session.find(SnapshotModel.class, id.toString());
            SnapshotOwnerModel snapshotOwner = session.find(SnapshotOwnerModel.class, id.toString());
            if (snapshotModel == null || snapshotOwner == null) {
                return null;
            }
            return new SnapshotStatus.Impl(new Snapshot.Impl(id, URI.create(snapshotOwner.getOwnerId())),
                    snapshotModel.getSnapshotState());
        }
    }

    @Override
    public void finalize(Snapshot snapshot) {
        try (Session session = storage.getSessionFactory().openSession()) {
            Transaction tx = session.beginTransaction();
            String snapshotId = snapshot.id().toString();
            SnapshotModel snapshotModel = session.find(SnapshotModel.class, snapshotId);
            if (snapshotModel == null) {
                throw new SnapshotException(Status.NOT_FOUND.asException());
            }
            if (snapshotModel.getSnapshotState() == SnapshotStatus.State.ERRORED) {
                return;
            }
            snapshotModel.setSnapshotState(SnapshotStatus.State.FINALIZED);

            List<WhiteboardModel> whiteboards = SessionHelper.getWhiteboardModels(snapshotId, session);

            for (WhiteboardModel wbModel : whiteboards) {
                List<WhiteboardFieldModel> resultListWhiteboardField =
                        SessionHelper.getNotCompletedWhiteboardFields(wbModel.getWbId(), session);
                if (resultListWhiteboardField.isEmpty()) {
                    wbModel.setWbState(WhiteboardStatus.State.COMPLETED);
                } else {
                    wbModel.setWbState(WhiteboardStatus.State.NOT_COMPLETED);
                }
            }

            try {
                session.update(snapshotModel);
                whiteboards.forEach(session::update);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw new SnapshotException(e);
            }
        }
    }

    @Override
    public void error(Snapshot snapshot) {
        try (Session session = storage.getSessionFactory().openSession()) {
            Transaction tx = session.beginTransaction();
            String snapshotId = snapshot.id().toString();
            SnapshotModel snapshotModel = session.find(SnapshotModel.class, snapshotId);
            if (snapshotModel == null) {
                throw new SnapshotException(Status.NOT_FOUND.asException());
            }
            snapshotModel.setSnapshotState(SnapshotStatus.State.ERRORED);

            List<WhiteboardModel> resultList = SessionHelper.getWhiteboardModels(snapshotId, session);
            resultList.forEach(wb -> wb.setWbState(WhiteboardStatus.State.ERRORED));

            try {
                session.update(snapshotModel);
                for (WhiteboardModel wbModel : resultList) {
                    session.update(wbModel);
                }
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw new SnapshotException(e);
            }
        }
    }

    @Override
    public void prepare(SnapshotEntry entry, List<String> dependentEntryIds) {
        try (Session session = storage.getSessionFactory().openSession()) {
            Transaction tx = session.beginTransaction();
            String snapshotId = entry.snapshot().id().toString();
            String entryId = entry.id();
            SnapshotEntryModel snapshotEntryModel = session.find(SnapshotEntryModel.class,
                    new SnapshotEntryModel.SnapshotEntryPk(snapshotId, entryId));
            if (snapshotEntryModel != null) {
                throw new SnapshotException(Status.INVALID_ARGUMENT.asException());
            }
            snapshotEntryModel = new SnapshotEntryModel(snapshotId, entryId,
                    entry.storage().toString(), true, SnapshotEntryStatus.State.IN_PROGRESS);
            List<EntryDependenciesModel> depModelList = new ArrayList<>();
            dependentEntryIds.forEach(id -> depModelList.add(new EntryDependenciesModel(snapshotId, id, entryId)));
            try {
                session.save(snapshotEntryModel);
                depModelList.forEach(session::save);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw new SnapshotException(e);
            }
        }
    }

    @Nullable
    @Override
    public SnapshotEntry resolveEntry(Snapshot snapshot, String id) {
        try (Session session = storage.getSessionFactory().openSession()) {
            String snapshotId = snapshot.id().toString();
            SnapshotEntryModel snapshotEntryModel = session.find(SnapshotEntryModel.class,
                    new SnapshotEntryModel.SnapshotEntryPk(snapshotId, id));
            if (snapshotEntryModel == null) {
                throw new SnapshotException(Status.NOT_FOUND.asException());
            }
            return new SnapshotEntry.Impl(id, URI.create(snapshotEntryModel.getStorageUri()), snapshot);
        }
    }

    @Nullable
    @Override
    public SnapshotEntryStatus resolveEntryStatus(Snapshot snapshot, String id) {
        try (Session session = storage.getSessionFactory().openSession()) {
            String snapshotId = snapshot.id().toString();
            SnapshotEntryModel snapshotEntryModel = session.find(SnapshotEntryModel.class,
                    new SnapshotEntryModel.SnapshotEntryPk(snapshotId, id));
            if (snapshotEntryModel == null) {
                throw new SnapshotException(Status.NOT_FOUND.asException());
            }

            List<String> dependentEntryIds = SessionHelper.getEntryDependenciesName(snapshotEntryModel, session);
            SnapshotEntry entry = new SnapshotEntry.Impl(id, URI.create(snapshotEntryModel.getStorageUri()), snapshot);
            return new SnapshotEntryStatus.Impl(snapshotEntryModel.isEmpty(),snapshotEntryModel.getEntryState(),entry,
                    Set.copyOf(dependentEntryIds));
        }
    }

    @Override
    public void commit(SnapshotEntry entry, boolean empty) {
        try (Session session = storage.getSessionFactory().openSession()) {
            Transaction tx = session.beginTransaction();
            String snapshotId = entry.snapshot().id().toString();
            String entryId = entry.id();
            SnapshotEntryModel snapshotEntryModel = session.find(SnapshotEntryModel.class,
                    new SnapshotEntryModel.SnapshotEntryPk(snapshotId, entryId));
            if (snapshotEntryModel == null) {
                throw new SnapshotException(Status.NOT_FOUND.asException());
            }
            snapshotEntryModel.setEntryState(SnapshotEntryStatus.State.FINISHED);
            snapshotEntryModel.setEmpty(empty);
            try {
                session.update(snapshotEntryModel);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw new SnapshotException(e);
            }
        }
    }
}
