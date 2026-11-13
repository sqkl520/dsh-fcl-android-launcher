/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tungsten.fclcore.event;

import com.tungsten.fclcore.util.Logging;
import com.tungsten.fclcore.util.SimpleMultimap;

import java.lang.ref.WeakReference;
import java.util.EnumMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class EventManager<T extends Event> {

    private final SimpleMultimap<EventPriority, Consumer<T>, CopyOnWriteArraySet<Consumer<T>>> handlers
            = new SimpleMultimap<>(() -> new EnumMap<>(EventPriority.class), CopyOnWriteArraySet::new);

    public Consumer<T> registerWeak(Consumer<T> consumer) {
        register(new WeakListener(consumer));
        return consumer;
    }

    public Consumer<T> registerWeak(Consumer<T> consumer, EventPriority priority) {
        register(new WeakListener(consumer), priority);
        return consumer;
    }

    public void register(Consumer<T> consumer) {
        register(consumer, EventPriority.NORMAL);
    }

    public synchronized void register(Consumer<T> consumer, EventPriority priority) {
        if (!handlers.get(priority).contains(consumer))
            handlers.put(priority, consumer);
    }

    public void register(Runnable runnable) {
        register(new RunnableListener<>(runnable));
    }

    public void register(Runnable runnable, EventPriority priority) {
        register(new RunnableListener<>(runnable), priority);
    }

    /**
     * @implNote 单个 handler 抛异常不得中断后续投递（见 round31 / slice-11-dc-f3）。
     * 只隔离 Exception；Error 继续向上传播 —— 那是"进程该停"的信号。
     */
    public synchronized Event.Result fireEvent(T event) {
        for (EventPriority priority : EventPriority.values()) {
            for (Consumer<T> handler : handlers.get(priority)) {
                try {
                    handler.accept(event);
                } catch (Exception e) {
                    Logging.LOG.log(Level.SEVERE, "Event handler failed for " + event.getClass().getName()
                            + " at priority " + priority + "; delivering to the remaining handlers", e);
                }
            }
        }

        if (event.hasResult())
            return event.getResult();
        else
            return Event.Result.DEFAULT;
    }

    public synchronized void unregister(Consumer<T> consumer) {
        handlers.removeValue(consumer);
    }

    /**
     * @implNote 按**被包装 consumer 的引用**判定相等（见 round31 / slice-11-dc-f2）。
     * 修复前没有 equals/hashCode，而每次 registerWeak 都 new 一个包装，于是
     * `register` 的 contains 守卫恒为 false、同一 consumer 被投递多次。
     * hash 在构造时定型：引用被回收后不得"换 hash"。
     */
    private class WeakListener implements Consumer<T> {
        private final WeakReference<Consumer<T>> ref;
        private final int hash;

        public WeakListener(Consumer<T> listener) {
            this.ref = new WeakReference<>(listener);
            this.hash = System.identityHashCode(listener);
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof EventManager.WeakListener
                    && ref.get() != null && ref.get() == ((EventManager<?>.WeakListener) o).ref.get());
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public void accept(T t) {
            Consumer<T> listener = ref.get();
            if (listener == null) {
                unregister(this);
            } else {
                listener.accept(t);
            }
        }
    }

    /** {@code register(Runnable)} 的固定包装；原先每调用一次就新建一个 lambda，绕过去重。 */
    private static final class RunnableListener<T extends Event> implements Consumer<T> {
        private final Runnable runnable;

        RunnableListener(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof EventManager.RunnableListener
                    && runnable == ((EventManager.RunnableListener<?>) o).runnable);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(runnable);
        }

        @Override
        public void accept(T t) {
            runnable.run();
        }
    }
}
