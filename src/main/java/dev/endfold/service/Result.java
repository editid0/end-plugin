package dev.endfold.service;

import java.util.Objects;
import java.util.function.Function;

/** Outcome of a service operation: either a value or a typed {@link Failure} (never both). */
public final class Result<T> {
    private final T value;
    private final Failure failure;

    private Result(T value, Failure failure) {
        this.value = value;
        this.failure = failure;
    }

    public static <T> Result<T> ok(T value) { return new Result<>(value, null); }

    public static <T> Result<T> fail(Failure failure) { return new Result<>(null, Objects.requireNonNull(failure)); }

    public boolean isOk() { return failure == null; }

    public T value() {
        if (failure != null) throw new IllegalStateException("Result is a failure: " + failure);
        return value;
    }

    public Failure failure() { return failure; }

    public <R> Result<R> map(Function<T, R> f) { return isOk() ? ok(f.apply(value)) : fail(failure); }

    @Override public String toString() { return isOk() ? "Ok(" + value + ")" : "Fail(" + failure + ")"; }
}
