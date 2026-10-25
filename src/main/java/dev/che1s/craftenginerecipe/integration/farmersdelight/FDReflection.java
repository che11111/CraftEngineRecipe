package dev.che1s.craftenginerecipe.integration.farmersdelight;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 反射始终限定在可选插件的类加载器中；FD 类不会进入 CER 的方法签名。 */
final class FDReflection {
    private final ClassLoader loader;
    private final Map<String, Optional<Class<?>>> classes = new ConcurrentHashMap<>();
    private final Map<Call, Optional<Method>> methods = new ConcurrentHashMap<>();
    private final Map<Call, Optional<Method>> declaredMethods = new ConcurrentHashMap<>();
    private final Map<Member, Optional<Field>> fields = new ConcurrentHashMap<>();

    FDReflection(ClassLoader loader) { this.loader = loader; }

    FDReflection(ClassLoader loader, Map<String, Class<?>> bindings) {
        this(loader);
        bindings.forEach((name, type) -> this.classes.put(name, Optional.of(type)));
    }

    Class<?> type(String name) {
        return classes.computeIfAbsent(name, key -> {
            try { return Optional.of(Class.forName(key, false, this.loader)); }
            catch (ClassNotFoundException | LinkageError absent) { return Optional.empty(); }
        }).orElse(null);
    }

    Object call(Object receiver, String name, Object... arguments) {
        if (receiver == null) return null;
        Method method = method(receiver, name, arguments);
        if (method == null) return null;
        try {
            // 包级私有附属实现中的公开方法也可能实现公开接口。
            if (!method.canAccess(receiver instanceof Class<?> ? null : receiver)) method.trySetAccessible();
            return method.invoke(receiver instanceof Class<?> ? null : receiver, arguments);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("FarmersDelight " + name + " failed", failure.getCause());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("Cannot call FarmersDelight " + name, failure);
        }
    }

    boolean supports(Object receiver, String name, Object... arguments) {
        return receiver != null && method(receiver, name, arguments) != null;
    }

    /** 对版本敏感的 GUI 内部结构应与公开来源 API 分离。 */
    Object callDeclared(Object receiver, String name, Object... arguments) {
        if (receiver == null) return null;
        Method method = declaredMethod(receiver, name, arguments);
        if (method == null) return null;
        try {
            Object instance = receiver instanceof Class<?> ? null : receiver;
            if (!method.canAccess(instance)) method.trySetAccessible();
            return method.invoke(instance, arguments);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("FarmersDelight " + name + " failed", failure.getCause());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("Cannot call FarmersDelight " + name, failure);
        }
    }

    boolean supportsDeclared(Object receiver, String name, Object... arguments) {
        return receiver != null && declaredMethod(receiver, name, arguments) != null;
    }

    Object field(Object receiver, String name) {
        if (receiver == null) return null;
        Class<?> type = receiver instanceof Class<?> c ? c : receiver.getClass();
        Field field = this.fields.computeIfAbsent(new Member(type, name), ignored -> {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try { return Optional.of(current.getDeclaredField(name)); }
                catch (NoSuchFieldException absent) { /* 继续尝试父类 */ }
            }
            return Optional.empty();
        }).orElse(null);
        if (field == null) return null;
        try {
            Object instance = receiver instanceof Class<?> ? null : receiver;
            if (!field.canAccess(instance)) field.trySetAccessible();
            return field.get(instance);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("Cannot read FarmersDelight " + name, failure);
        }
    }

    private Method declaredMethod(Object receiver, String name, Object[] arguments) {
        Class<?> type = receiver instanceof Class<?> c ? c : receiver.getClass();
        Call key = new Call(type, name, Arrays.stream(arguments).map(a -> a == null ? Void.class : a.getClass()).toList());
        return this.declaredMethods.computeIfAbsent(key, ignored -> {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                Optional<Method> found = Arrays.stream(current.getDeclaredMethods())
                        .filter(m -> m.getName().equals(name) && compatible(m.getParameterTypes(), arguments)).findFirst();
                if (found.isPresent()) return found;
            }
            return Optional.empty();
        }).orElse(null);
    }

    private Method method(Object receiver, String name, Object[] arguments) {
        Class<?> type = receiver instanceof Class<?> c ? c : receiver.getClass();
        Call key = new Call(type, name, Arrays.stream(arguments).map(a -> a == null ? Void.class : a.getClass()).toList());
        return this.methods.computeIfAbsent(key, ignored -> Arrays.stream(type.getMethods())
                .filter(m -> m.getName().equals(name) && compatible(m.getParameterTypes(), arguments))
                .findFirst()).orElse(null);
    }

    private static boolean compatible(Class<?>[] parameters, Object[] arguments) {
        if (parameters.length != arguments.length) return false;
        for (int i = 0; i < parameters.length; i++) {
            if (arguments[i] == null) {
                if (parameters[i].isPrimitive()) return false;
            } else if (!boxed(parameters[i]).isInstance(arguments[i])) return false;
        }
        return true;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    private record Call(Class<?> type, String name, List<Class<?>> arguments) {}
    private record Member(Class<?> type, String name) {}
}
