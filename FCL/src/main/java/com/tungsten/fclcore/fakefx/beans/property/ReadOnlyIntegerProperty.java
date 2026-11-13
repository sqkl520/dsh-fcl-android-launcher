package com.tungsten.fclcore.fakefx.beans.property;

import com.tungsten.fclcore.fakefx.beans.InvalidationListener;
import com.tungsten.fclcore.fakefx.beans.WeakInvalidationListener;
import com.tungsten.fclcore.fakefx.beans.binding.IntegerExpression;

public abstract class ReadOnlyIntegerProperty extends IntegerExpression
        implements ReadOnlyProperty<Number> {

    /**
     * The constructor of {@code ReadOnlyIntegerProperty}.
     */
    public ReadOnlyIntegerProperty() {
    }


    /**
     * Returns a string representation of this {@code ReadOnlyIntegerProperty} object.
     * @return a string representation of this {@code ReadOnlyIntegerProperty} object.
     */
    @Override
    public String toString() {
        final Object bean = getBean();
        final String name = getName();
        final StringBuilder result = new StringBuilder(
                "ReadOnlyIntegerProperty [");
        if (bean != null) {
            result.append("bean: ").append(bean).append(", ");
        }
        if ((name != null) && !name.equals("")) {
            result.append("name: ").append(name).append(", ");
        }
        result.append("value: ").append(get()).append("]");
        return result.toString();
    }

    public static <T extends Number> ReadOnlyIntegerProperty readOnlyIntegerProperty(final ReadOnlyProperty<T> property) {
        if (property == null) {
            throw new NullPointerException("Property cannot be null");
        }

        return property instanceof ReadOnlyIntegerProperty ? (ReadOnlyIntegerProperty) property:
           new ReadOnlyIntegerPropertyBase() {
            private volatile boolean valid = true;
            private final InvalidationListener listener = observable -> {
                if (valid) {
                    valid = false;
                    fireValueChangedEvent();
                }
            };

            {
                property.addListener(new WeakInvalidationListener(listener));
            }

            /**
             * Returns the primitive projection of the upstream value.
             *
             * <p>An {@code int} has no "no value" state, so an unset ({@code null}) upstream
             * collapses to the type default here. This adapter deliberately does <b>not</b> override
             * {@code getValue()}: the base {@code IntegerExpression} keeps its original
             * {@code return get();} body, so {@code boxed(get()).equals(getValue())} holds
             * <b>unconditionally</b> -- the same family invariant every other member satisfies.
             *
             * <p>{@code f02} ("upstream unset" must stay distinguishable from "upstream holds the
             * zero value") is therefore <b>not</b> served here, and not on {@code getValue()} either:
             * it is served by {@link #asObject()}, whose reference-typed {@code get()} returns the
             * raw upstream value verbatim, {@code null} included. See that method for the contract.
             */
            @Override
            public int get() {
                final T value = nullableUpstream();
                return value == null ? 0 : value.intValue();
            }

            /**
             * Reads the upstream property and re-arms this adapter's invalidation gate.
             *
             * <p>Both {@link #get()} and {@link #asObject()} go through here. Round-tripping the
             * read through one place is what keeps notification timing identical to the previous
             * implementation (which re-armed the gate inside the {@code getValue()} override):
             * a read anywhere resets {@code valid}, so the next upstream change notifies again.
             *
             * @return the upstream value exactly as the wrapped property reports it, possibly
             *         {@code null}
             */
            private T nullableUpstream() {
                valid = true;
                return property.getValue();
            }

            /**
             * Returns the reference-typed view of this adapter -- the one channel where an unset
             * upstream stays observable as {@code null}.
             *
             * <p>Why this override exists: the inherited implementation projects through
             * {@code getValue()}, which is {@code boxed(get())} and therefore can never be
             * {@code null}. To keep the {@code f02} semantics without breaking the family
             * invariant, this view reads the <b>raw upstream</b> instead. Both projections of the
             * view itself still agree, because {@code ObjectExpression.getValue()} is
             * {@code return get();} -- the invariant is intact one level down as well.
             *
             * <p>Contract, pinned by {@code R31Fix248Test}:
             *
             * <pre>
             *   get()           == upstream value verbatim, null included
             *   getValue()      == get()                (reference type: nothing to collapse)
             *   repeated calls  == one cached instance  (f03 idempotency, no subscription growth)
             *   upstream change -&gt; this view is invalidated (weak listener on the adapter)
             * </pre>
             *
             * <p>Blast radius: neither {@code asObject()} nor the static factory has a production
             * call site in this repository (both are reached only from tests), so no shipped
             * runtime path changes behaviour.
             */
            private ReadOnlyObjectProperty<Integer> nullableView;

            @Override
            public ReadOnlyObjectProperty<Integer> asObject() {
                if (nullableView == null) {
                    final ReadOnlyIntegerProperty self = this;
                    nullableView = new ReadOnlyObjectPropertyBase<Integer>() {
                        private volatile boolean valid = true;
                        private final InvalidationListener viewListener = observable -> {
                            if (valid) {
                                valid = false;
                                fireValueChangedEvent();
                            }
                        };

                        {
                            self.addListener(new WeakInvalidationListener(viewListener));
                        }

                        @Override
                        public Integer get() {
                            valid = true;
                            final T v = nullableUpstream();
                            return v == null ? null : v.intValue();
                        }

                        @Override
                        public Object getBean() {
                            return null; // Virtual property, no bean
                        }

                        @Override
                        public String getName() {
                            return property.getName();
                        }
                    };
                }
                return nullableView;
            }
            @Override
            public Object getBean() {
                return null; // Virtual property, no bean
            }

            @Override
            public String getName() {
                return property.getName();
            }
        };
    }

    private ReadOnlyObjectProperty<Integer> asObject;

    @Override
    public ReadOnlyObjectProperty<Integer> asObject() {
        if (asObject == null) {
            asObject = newAsObject();
        }
        return asObject;
    }

    private ReadOnlyObjectProperty<Integer> newAsObject() {
        return new ReadOnlyObjectPropertyBase<Integer>() {

            private volatile boolean valid = true;
            private final InvalidationListener listener = observable -> {
                if (valid) {
                    valid = false;
                    fireValueChangedEvent();
                }
            };

            {
                ReadOnlyIntegerProperty.this.addListener(new WeakInvalidationListener(listener));
            }

            @Override
            public Object getBean() {
                return null; // Virtual property, does not exist on a bean
            }

            @Override
            public String getName() {
                return ReadOnlyIntegerProperty.this.getName();
            }

            @Override
            public Integer get() {
                valid = true;
                return ReadOnlyIntegerProperty.this.getValue();
            }
        };
    };

}
