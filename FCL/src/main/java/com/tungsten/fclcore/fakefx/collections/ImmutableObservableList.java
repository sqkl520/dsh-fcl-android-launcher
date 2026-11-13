package com.tungsten.fclcore.fakefx.collections;

import com.tungsten.fclcore.fakefx.beans.InvalidationListener;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;

/**
 * An immutable {@link ObservableList} view over a fixed array of elements.
 *
 * <p>This class is the "several dependencies" result of {@code Binding.getDependencies()} and
 * of the {@code Bindings.create*Binding} helpers, so it reaches callers as a plain writable {@code ObservableList}. Read on before assuming that
 * every write attempt is rejected.
 *
 * <h2>What "immutable" covers</h2>
 *
 * The class overrides <b>exactly six</b> write methods to throw
 * {@link UnsupportedOperationException}: {@code addAll(E...)}, {@code setAll(E...)},
 * {@code setAll(Collection)}, {@code removeAll(E...)}, {@code retainAll(E...)} and
 * {@code remove(int from, int to)} - the six write operations declared by
 * {@link ObservableList} itself.
 *
 * <p>The write methods inherited from {@link java.util.AbstractList} and
 * {@link java.util.AbstractCollection} are <b>not</b> overridden and therefore do <b>not</b>
 * all throw. The content of the list can never change - the backing array is {@code final} and
 * {@link #get(int)} rejects out-of-range indices - so there is no data corruption. What is
 * missing is the <i>signal</i>: when a call happens to be a no-op because of the number of
 * elements, it returns {@code false} / {@code void} and the caller cannot tell "written" apart
 * from "silently ignored".
 *
 * <p>Making every write throw was considered and rejected: callers routinely run defensive
 * {@code clear()} / {@code removeIf(...)} over a list they do not own, and turning those into
 * exceptions would break code paths that a test of this class would never exercise.
 *
 * <h2>The silent cases, measured</h2>
 *
 * Real measurements of this class under JDK 17.0.20.1. The same table is asserted by
 * {@code R31EmptyListContractTest}, so changing any of these outcomes makes that test fail.
 *
 * <pre>
 * operation                         size()==0        size()==1
 * add(E)                            UOE              UOE
 * add(int, E)                       UOE              UOE
 * addAll(Collection, non-empty)     UOE              UOE
 * addAll(Collection, empty)         silent(false)    silent(false)
 * addAll(int, Collection empty)     silent(false)    silent(false)
 * addAll(int, Collection non-empty) UOE              UOE
 * set(int, E)                       UOE              UOE
 * remove(int)                       UOE              UOE
 * remove(Object)                    silent(false)    UOE
 * clear()                           silent(void)     UOE
 * removeAll(Collection, empty)      silent(false)    silent(false)
 * removeAll(Collection, no match)   silent(false)    silent(false)
 * retainAll(Collection)             silent(false)    UOE
 * removeIf(predicate false)         silent(false)    silent(false)
 * removeIf(predicate true)          silent(false)    UOE
 * sort(Comparator)                  silent(void)     UOE
 * replaceAll(operator)              silent(void)     UOE
 * subList(0, 0).clear()             silent(void)     silent(void)
 * subList(0, size).clear()          silent(void)     UOE
 * addAll(E...)                      UOE              UOE
 * setAll(E...)                      UOE              UOE
 * setAll(Collection)                UOE              UOE
 * removeAll(E...)                   UOE              UOE
 * retainAll(E...)                   UOE              UOE
 * remove(int, int)                  UOE              UOE
 * iterator().remove() (no next)     IllegalState     IllegalState
 * iterator() drained, then remove() silent(void)     UOE
 * listIterator().add(E)             UOE              UOE
 * </pre>
 *
 * <p>{@code UOE} = {@link UnsupportedOperationException}, {@code IllegalState} =
 * {@link IllegalStateException}, {@code silent(...)} = the method returned that value, threw
 * nothing, and left the list unchanged.
 *
 * <p><b>Read the table as a whole:</b> "silent" is conditional on the operation being a no-op
 * because of the element count; it is not a property of this class. The same {@code clear()}
 * that returns normally on an empty list throws on a one-element list, and {@code size()==1}
 * is still silent for six of the operations above.
 *
 * @param <E> the element type
 * @see FXCollections#emptyObservableList() for the shared-empty variant, which behaves the same
 *      way except for {@code iterator().remove()} (that one throws
 *      {@link IllegalStateException} here and {@link UnsupportedOperationException} there)
 */
public class ImmutableObservableList<E> extends AbstractList<E> implements ObservableList<E> {

    private final E[] elements;

    public ImmutableObservableList(E... elements) {
        this.elements = ((elements == null) || (elements.length == 0))?
                null : Arrays.copyOf(elements, elements.length);
    }

    @Override
    public void addListener(InvalidationListener listener) {
        // no-op
    }

    @Override
    public void removeListener(InvalidationListener listener) {
        // no-op
    }

    @Override
    public void addListener(ListChangeListener<? super E> listener) {
        // no-op
    }

    @Override
    public void removeListener(ListChangeListener<? super E> listener) {
        // no-op
    }

    @Override
    public boolean addAll(E... elements) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean setAll(E... elements) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean setAll(Collection<? extends E> col) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(E... elements) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(E... elements) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void remove(int from, int to) {
        throw new UnsupportedOperationException();
    }

    @Override
    public E get(int index) {
        if ((index < 0) || (index >= size())) {
            throw new IndexOutOfBoundsException();
        }
        return elements[index];
    }

    @Override
    public int size() {
        return (elements == null)? 0 : elements.length;
    }

}
