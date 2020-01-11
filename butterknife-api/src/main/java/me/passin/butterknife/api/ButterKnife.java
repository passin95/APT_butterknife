package me.passin.butterknife.api;

import android.app.Activity;
import android.app.Dialog;
import android.util.Log;
import android.view.View;

import androidx.annotation.CheckResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author: zbb
 * @date: 2020/1/3 9:33
 * @desc:
 */
public final class ButterKnife {
    private ButterKnife() {
        throw new AssertionError("No instances.");
    }

    private static final String TAG = "ButterKnife";

    private static boolean debug = false;

    public static void setDebug(boolean debug) {
        ButterKnife.debug = debug;
    }

    static final Map<Class<?>, Constructor<? extends Unbinder>> BINDINGS = new LinkedHashMap<>();

    @NonNull
    @UiThread
    public static Unbinder bind(@NonNull Activity target) {
        View sourceView = target.getWindow().getDecorView();
        return bind(target, sourceView);
    }

    @NonNull
    @UiThread
    public static Unbinder bind(@NonNull View target) {
        return bind(target, target);
    }

    @NonNull
    @UiThread
    public static Unbinder bind(@NonNull Dialog target) {
        View sourceView = target.getWindow().getDecorView();
        return bind(target, sourceView);
    }

    @NonNull
    @UiThread
    public static Unbinder bind(@NonNull Object target, @NonNull Activity source) {
        View sourceView = source.getWindow().getDecorView();
        return bind(target, sourceView);
    }

    @NonNull
    @UiThread
    public static Unbinder bind(@NonNull Object target, @NonNull Dialog source) {
        View sourceView = source.getWindow().getDecorView();
        return bind(target, sourceView);
    }

    /**
     * 绑定 source 中的视图到目标类 target 中。
     */
    @NonNull
    @UiThread
    public static Unbinder bind(@NonNull Object target, @NonNull View source) {
        Class<?> targetClass = target.getClass();
        if (debug) {
            Log.d(TAG, "查找绑定" + targetClass.getName());
        }
        Constructor<? extends Unbinder> constructor = findBindingConstructorForClass(targetClass);

        if (constructor == null) {
            return Unbinder.EMPTY;
        }

        try {
            return constructor.newInstance(target, source);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Unable to invoke " + constructor, e);
        } catch (InstantiationException e) {
            throw new RuntimeException("Unable to invoke " + constructor, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException("Unable to create binding instance.", cause);
        }
    }

    @Nullable
    @CheckResult
    @UiThread
    private static Constructor<? extends Unbinder> findBindingConstructorForClass(Class<?> cls) {
        // 对绑定类的构造器进行缓存，因此绑定类只在整个生命周期
        Constructor<? extends Unbinder> bindingCtor = BINDINGS.get(cls);
        if (bindingCtor != null || BINDINGS.containsKey(cls)) {
            if (debug) {
                Log.d(TAG, cls.getName() + "缓存已存在绑定映射中");
            }
            return bindingCtor;
        }
        String clsName = cls.getName();
        // 不应该对 framework 层的类进行绑定。
        if (clsName.startsWith("android.") || clsName.startsWith("java.")
                || clsName.startsWith("androidx.")) {
            if (debug) Log.d(TAG, "到达 framework 类。放弃搜索");
            return null;
        }
        try {
            // 该类会在编译阶段自动生成。API 和 APT 需要协议好反射类的格式。
            Class<?> bindingClass = cls.getClassLoader().loadClass(clsName + "_ViewBinding");
            bindingCtor = (Constructor<? extends Unbinder>) bindingClass.getConstructor(cls, View.class);
            if (debug) {
                Log.d(TAG, "查找到" + cls.getName() + "的绑定类");
            }
        } catch (ClassNotFoundException e) {
            if (debug) Log.d(TAG, "未发现，尝试从父类查找：" + cls.getSuperclass().getName());
            bindingCtor = findBindingConstructorForClass(cls.getSuperclass());
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Unable to find binding constructor for " + clsName, e);
        }
        BINDINGS.put(cls, bindingCtor);
        return bindingCtor;
    }
}

