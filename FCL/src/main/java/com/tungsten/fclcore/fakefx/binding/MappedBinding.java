package com.tungsten.fclcore.fakefx.binding;

import com.tungsten.fclcore.fakefx.beans.value.ObservableValue;

import java.util.Objects;
import java.util.function.Function;

public class MappedBinding<S, T> extends LazyObjectBinding<T> {

    private final ObservableValue<S> source;
    private final Function<? super S, ? extends T> mapper;

    public MappedBinding(ObservableValue<S> source, Function<? super S, ? extends T> mapper) {
        this.source = Objects.requireNonNull(source, "source cannot be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper cannot be null");
    }

    @Override
    protected T computeValue() {
        // R31/slice-34-dc-03: a Function must be applied to every source value, null included.
        // Short-circuiting on null silently skips the mapper's own null handling (see also
        // util/fakefx/BindingMapping.MappedBinding, which never short-circuits).
        return mapper.apply(source.getValue());
    }

    @Override
    protected Subscription observeSources() {
        return Subscription.subscribeInvalidations(source, this::invalidate); // start observing source
    }
}
