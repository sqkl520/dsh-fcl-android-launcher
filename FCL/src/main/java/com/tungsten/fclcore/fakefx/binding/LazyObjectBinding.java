package com.tungsten.fclcore.fakefx.binding;

import com.tungsten.fclcore.fakefx.beans.InvalidationListener;
import com.tungsten.fclcore.fakefx.beans.binding.ObjectBinding;
import com.tungsten.fclcore.fakefx.beans.value.ChangeListener;

/**
 * Extends {@link ObjectBinding} with the ability to lazily register and eagerly unregister listeners on its
 * dependencies.
 *
 * @param <T> the type of the wrapped {@code Object}
 */
abstract class LazyObjectBinding<T> extends ObjectBinding<T> {

    private Subscription subscription;
    private boolean wasObserved;

    @Override
    public void addListener(ChangeListener<? super T> listener) {
        super.addListener(listener);

        updateSubscriptionAfterAdd();
    }

    @Override
    public void removeListener(ChangeListener<? super T> listener) {
        super.removeListener(listener);

        updateSubscriptionAfterRemove();
    }

    @Override
    public void addListener(InvalidationListener listener) {
        super.addListener(listener);

        updateSubscriptionAfterAdd();
    }

    @Override
    public void removeListener(InvalidationListener listener) {
        super.removeListener(listener);

        updateSubscriptionAfterRemove();
    }

    @Override
    protected boolean allowValidation() {
        return isObserved();
    }

    /**
     * Called after a listener was added to start observing inputs if they're not observed already.
     */
    private void updateSubscriptionAfterAdd() {
        if (!wasObserved) { // was first observer registered?
            subscription = observeSources(); // start observing source

            /*
             * Although the act of registering a listener already attempts to make
             * this binding valid, allowValidation won't allow it as the binding is
             * not observed yet. This is because isObserved will not yet return true
             * when the process of registering the listener hasn't completed yet.
             *
             * As the binding must be valid after it becomes observed the first time
             * 'get' is called again.
             *
             * See com.sun.javafx.binding.ExpressionHelper (which is used
             * by ObjectBinding) where it will do a call to ObservableValue#getValue
             * BEFORE adding the actual listener. This results in ObjectBinding#get
             * to be called in which the #allowValidation call will block it from
             * becoming valid as the condition is "isObserved()"; this is technically
             * correct as the listener wasn't added yet, but means we must call
             * #get again to make this binding valid.
             */

            // R31/slice-34-dc-02: get() runs computeValue(), whose mapper is supplied by the caller
            // and may throw. Previously the throw left subscription != null while wasObserved stayed
            // false: updateSubscriptionAfterRemove() is gated on wasObserved, so the subscription was
            // never unsubscribed, and the next addListener subscribed to the same source a second
            // time. Resetting wasObserved in a finally keeps the pair (wasObserved, subscription)
            // in lockstep regardless of whether get() succeeds.
            try {
                get(); // make binding valid as source wasn't tracked until now
            } finally {
                wasObserved = true;
            }
        }
    }

    /**
     * Called after a listener was removed to stop observing inputs if this was the last listener
     * observing this binding.
     */
    private void updateSubscriptionAfterRemove() {
        if (wasObserved && !isObserved()) { // was last observer unregistered?
            subscription.unsubscribe();
            subscription = null;
            invalidate(); // make binding invalid as source is no longer tracked
            wasObserved = false;
        }
    }

    /**
     * Called when this binding was previously not observed and a new observer was added. Implementors must return a
     * {@link Subscription} which will be cancelled when this binding no longer has any observers.
     *
     * @return a {@link Subscription} which will be cancelled when this binding no longer has any observers, never null
     */
    protected abstract Subscription observeSources();
}
