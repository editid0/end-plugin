package dev.endfold.service;

/** Thrown inside a transaction to abort it with a typed failure; converted to a {@link Result} by the service. */
final class ServiceException extends RuntimeException {
    final Failure failure;

    ServiceException(Failure failure) {
        super(failure.name(), null, false, false);
        this.failure = failure;
    }
}
