package ru.yandex.cloud.ml.platform.lzy.kharon.workflow.storage;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.flywaydb.core.Flyway;
import ru.yandex.cloud.ml.platform.lzy.kharon.workflow.configs.WorkflowDatabaseConfig;

import java.sql.Connection;
import java.sql.SQLException;

@Singleton
@Requires(property = "kharon.workflow.database.url")
public class StorageImpl implements Storage {
    private static final String VALIDATION_QUERY_SQL = "select 1";

    private final ComboPooledDataSource dataSource;

    @Inject
    public StorageImpl(WorkflowDatabaseConfig dbConfig) {
        this.dataSource = new ComboPooledDataSource();
        dataSource.setJdbcUrl(dbConfig.url());
        dataSource.setUser(dbConfig.username());
        dataSource.setPassword(dbConfig.password());

        dataSource.setMinPoolSize(dbConfig.minPoolSize());
        dataSource.setMaxPoolSize(dbConfig.maxPoolSize());

        dataSource.setTestConnectionOnCheckout(true);
        dataSource.setPreferredTestQuery(VALIDATION_QUERY_SQL);

        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/workflow/migrations")
                .load();
        flyway.migrate();
    }

    @Override
    public Connection connect() throws SQLException {
        var conn = dataSource.getConnection();
        conn.setAutoCommit(true);
        return conn;
    }
}
