package com.tungsten.fclcore.fakefx.beans.value;

/**
 * A {@code WritableValue} is an entity that wraps a value that can be read and
 * set. In general this interface should not be implemented directly but one of
 * its sub-interfaces ({@code WritableBooleanValue} etc.).
 *
 * @see WritableBooleanValue
 * @see WritableDoubleValue
 * @see WritableFloatValue
 * @see WritableIntegerValue
 * @see WritableLongValue
 * @see WritableNumberValue
 * @see WritableObjectValue
 * @see WritableStringValue
 *
 * @param <T>
 *            The type of the wrapped value
 *
 *
 * @since JavaFX 2.0
 */
public interface WritableValue<T> {

    /**
     * Get the wrapped value.
     *
     * @return The current value
     */
    T getValue();

    /**
     * Sets the wrapped value.
     *
     * <p>Implementations backed by a <b>primitive</b> ({@code boolean}, {@code double},
     * {@code float}, {@code int}, {@code long}) cannot represent "no value", so they
     * normalise {@code null} to their type's default: this is a documented
     * normalisation, not an error. Callers therefore cannot distinguish
     * {@code setValue(null)} from {@code setValue(false)} / {@code setValue(0)} on those
     * properties, and writing {@code null} to a property that already holds the default
     * value produces no change notification at all (the underlying {@code set} short
     * circuits on equality). This is relied upon by the string-conversion
     * bidirectional binding, which writes the type default back when conversion of the
     * peer value fails.
     *
     * <p>Implementations backed by a <b>reference</b> type are expected to store
     * {@code null} as-is.
     *
     * @param value
     *            The new value; {@code null} is normalised to the type default by
     *            primitive-backed implementations and stored unchanged by
     *            reference-backed ones
     */
    void setValue(T value);

}
