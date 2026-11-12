package io.reggraeton.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * native mode: one instance of each glue class per test, created through its constructor with the
 * other glue objects injected, like cucumber-picocontainer did for the step definitions.
 */
public final class ScenarioScope {

    private final Map<Class<?>, Object> instances = new HashMap<>();
    private final Deque<Class<?>> creating = new ArrayDeque<>();

    /** Registers an object that glue constructors may ask for (e.g. the {@link Scenario}). */
    public <T> void register(Class<T> type, T instance) {
        instances.put(type, instance);
    }

    public <T> T get(Class<T> type) {
        Object existing = instances.get(type);
        if (existing != null) {
            return type.cast(existing);
        }
        if (creating.contains(type)) {
            throw new IllegalStateException("Cyclic constructor dependency: " + creating + " -> " + type.getName());
        }
        creating.push(type);
        try {
            Constructor<?> constructor = Arrays.stream(type.getDeclaredConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow(() -> new IllegalStateException("No constructor in " + type.getName()));
            constructor.setAccessible(true);
            Object[] args = Arrays.stream(constructor.getParameterTypes()).map(this::get).toArray();
            T instance = type.cast(constructor.newInstance(args));
            instances.put(type, instance);
            return instance;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Could not create " + type.getName(), cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not create " + type.getName(), e);
        } finally {
            creating.pop();
        }
    }
}
