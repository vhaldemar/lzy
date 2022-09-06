package ai.lzy.model.db;

import org.apache.logging.log4j.Logger;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;
import java.util.function.Consumer;

@SuppressWarnings({"BusyWait"})
public enum DbHelper {
    ;

    public interface Func<T> {
        T run() throws SQLException;
    }

    public interface ErrorAdapter<E extends Exception> {
        E fail(Exception e);
    }

    public static <T> boolean withRetries(RetryPolicy retryPolicy, Logger logger, Func<T> fn,
                                          Consumer<T> onSuccess, Consumer<Exception> onError)
    {
        /* it looks weird...
        final class Done extends Exception {}

        try {
            var result = withRetries(retryPolicy, logger, fn, ex -> {
                onError.accept(ex);
                return new Done();
            });
            onSuccess.accept(result);
        } catch (Done e) {
            // ignored
        }
        */

        int delay = 0;
        for (int attempt = 1; ; ++attempt) {
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ex) {
                    onError.accept(ex);
                    return false;
                }
            }

            try {
                var ret = fn.run();
                onSuccess.accept(ret);
                return true;
            } catch (PSQLException e) {
                if (canRetry(e)) {
                    delay = retryPolicy.getNextDelayMs();
                    if (delay >= 0) {
                        logger.error("Got retryable database error #{}: [{}] {}. Retry after {}ms.",
                            attempt, e.getSQLState(), e.getMessage(), delay);
                        continue;
                    } else {
                        logger.error("Got retryable database error: [{}] {}. Retries limit {} exceeded.",
                            e.getSQLState(), e.getMessage(), attempt);
                        onError.accept(new RetryCountExceededException(attempt));
                    }
                } else {
                    logger.error("Got non-retryable database error: [{}] {}.", e.getSQLState(), e.getMessage());
                    onError.accept(e);
                }
            } catch (Exception e) {
                logger.error("Got non-retryable error: {}.", e.getMessage(), e);
                onError.accept(e);
            }
            return false;
        }
    }

    public static <T, E extends Exception> T withRetries(RetryPolicy retryPolicy, Logger logger, Func<T> fn,
                                                         ErrorAdapter<E> error) throws E
    {
        int delay = 0;
        for (int attempt = 1; ; ++attempt) {
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    throw error.fail(e);
                }
            }

            try {
                return fn.run();
            } catch (PSQLException e) {
                if (canRetry(e)) {
                    delay = retryPolicy.getNextDelayMs();
                    if (delay >= 0) {
                        logger.error("Got retryable database error #{}: [{}] {}. Retry after {}ms.",
                            attempt, e.getSQLState(), e.getMessage(), delay);
                        //noinspection UnnecessaryContinue
                        continue;
                    } else {
                        logger.error("Got retryable database error: [{}] {}. Retries limit {} exceeded.",
                            e.getSQLState(), e.getMessage(), attempt);
                        throw error.fail(new RetryCountExceededException(attempt));
                    }
                } else {
                    logger.error("Got non-retryable database error: [{}] {}.", e.getSQLState(), e.getMessage());
                    throw error.fail(e);
                }
            } catch (Exception e) {
                logger.error("Got non-retryable error: {}.", e.getMessage(), e);
                throw error.fail(e);
            }
        }
    }

    interface RetryPolicy {
        int getNextDelayMs();
    }

    public static final class DefaultRetryPolicy implements RetryPolicy {
        private int attempts;
        private int nextDelay;
        private final int coeff;

        public DefaultRetryPolicy(int attempts, int nextDelay, int coeff) {
            this.attempts = attempts;
            this.nextDelay = nextDelay;
            this.coeff = coeff;
        }

        @Override
        public int getNextDelayMs() {
            if (--attempts < 0) {
                return -1;
            }
            var delay = nextDelay;
            nextDelay *= coeff;
            return delay;
        }
    }

    public static RetryPolicy defaultRetryPolicy() {
        return new DefaultRetryPolicy(3, 50, 2);
    }

    public static final class RetryCountExceededException extends Exception {
        public RetryCountExceededException(int count) {
            super("Database retries limit " + count + " exceeded.");
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final String PSQL_CannotSerializeTransaction = "40001";

    private static boolean canRetry(PSQLException e) {
        if (e.getSQLState() == null) {
            if (e.getCause() instanceof PSQLException ex) {
                e = ex;
            } else if (e.getCause() != null && e.getCause().getCause() instanceof PSQLException ex) {
                e = ex;
            } else {
                return false;
            }
        }

        if (PSQL_CannotSerializeTransaction.equals(e.getSQLState())) {
            return true;
        }

        return PSQLState.isConnectionError(e.getSQLState());
    }
}
