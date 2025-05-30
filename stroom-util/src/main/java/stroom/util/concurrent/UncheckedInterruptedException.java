package stroom.util.concurrent;

import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.NullSafe;

import java.util.Objects;

public class UncheckedInterruptedException extends RuntimeException {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(UncheckedInterruptedException.class);

    public UncheckedInterruptedException(final String message, final InterruptedException interruptedException) {
        super(message, interruptedException);
    }

    public UncheckedInterruptedException(final InterruptedException interruptedException) {
        super(interruptedException.getMessage(), interruptedException);
    }

    /**
     * Logs the error with using the msgSupplier to provide the log message, resets the interrupt flag
     * and returns a new {@link UncheckedInterruptedException} that can be rethrown.
     */
    public static UncheckedInterruptedException create(final InterruptedException e) {
        return create(e.getMessage(), e);
    }

    /**
     * Logs the error to DEBUG using the msgSupplier to provide the log message,
     * resets the interrupt flag
     * and returns a new {@link UncheckedInterruptedException} that can be rethrown.
     */
    public static UncheckedInterruptedException create(final String message,
                                                       final InterruptedException e) {
        LOGGER.debug(() ->
                Objects.requireNonNullElseGet(
                        message,
                        () -> NullSafe.getOrElse(e, Throwable::getMessage, "Interrupted")
                ));
        // Continue to interrupt the thread.
        Thread.currentThread().interrupt();
        return new UncheckedInterruptedException(message, e);
    }
}
