package com.tungsten.fclcore.fakefx.beans.property;

import com.tungsten.fclcore.fakefx.beans.binding.Bindings;
import com.tungsten.fclcore.fakefx.beans.value.WritableStringValue;
import com.tungsten.fclcore.fakefx.util.StringConverter;

import java.text.Format;

public abstract class StringProperty extends ReadOnlyStringProperty implements
        Property<String>, WritableStringValue {

    /**
     * Creates a default {@code StringProperty}.
     */
    public StringProperty() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setValue(String v) {
        set(v);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bindBidirectional(Property<String> other) {
        Bindings.bindBidirectional(this, other);
    }

    /**
     * Create a bidirectional binding between this {@code StringProperty} and another
     * arbitrary property. Relies on an implementation of {@code Format} for conversion.
     *
     * @param other
     *            the other {@code Property}
     * @param format
     *            the {@code Format} used to convert between this {@code StringProperty}
     *            and the other {@code Property}
     * @throws NullPointerException
     *             if {@code other} or {@code format} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code other} is {@code this}
     * @since JavaFX 2.1
     */
    public void bindBidirectional(Property<?> other, Format format) {
        Bindings.bindBidirectional(this, other, format);
    }

    public <T> void bindBidirectional(Property<T> other, StringConverter<T> converter) {
        Bindings.bindBidirectional(this, other, converter);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unbindBidirectional(Property<String> other) {
        Bindings.unbindBidirectional(this, other);
    }

    /**
     * Remove a bidirectional binding between this {@code Property} and another
     * one.
     *
     * If no bidirectional binding between the properties exists, calling this
     * method has no effect.
     *
     * @param other
     *            the other {@code Property}
     * @throws NullPointerException
     *             if {@code other} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code other} is {@code this}
     * @since JavaFX 2.1
     */
    public void unbindBidirectional(Object other) {
        Bindings.unbindBidirectional(this, other);
    }

    /**
     * Returns a string representation of this {@code StringProperty} object.
     * @return a string representation of this {@code StringProperty} object.
     */
    @Override
    public String toString() {
        final Object bean = getBean();
        final String name = getName();
        final StringBuilder result = new StringBuilder(
                "StringProperty [");
        if (bean != null) {
            result.append("bean: ").append(bean).append(", ");
        }
        if ((name != null) && (!name.equals(""))) {
            result.append("name: ").append(name).append(", ");
        }
        // R31/r31-s24-contract-01: StringPropertyBase.toString() inserts "bound, " when the property
        // is bound, but this implementation did not — so a JavaBeanStringProperty (which extends this
        // class and does not override toString) rendered a different shape for the same state.
        // Only the bound segment is added; the unbound shape stays byte-identical.
        if (isBound()) {
            result.append("bound, ");
        }
        result.append("value: ").append(get()).append("]");
        return result.toString();
    }
}
