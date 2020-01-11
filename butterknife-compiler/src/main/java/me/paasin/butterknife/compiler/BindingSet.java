package me.paasin.butterknife.compiler;

import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

import static com.google.auto.common.MoreElements.getPackage;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static me.paasin.butterknife.compiler.ButterKnifeProcessor.ACTIVITY_TYPE;
import static me.paasin.butterknife.compiler.ButterKnifeProcessor.DIALOG_TYPE;
import static me.paasin.butterknife.compiler.ButterKnifeProcessor.VIEW_TYPE;
import static me.paasin.butterknife.compiler.ButterKnifeProcessor.isSubtypeOfType;

/**
 * @author: zbb
 * @date: 2020/1/2 15:31
 * @desc:
 */
final class BindingSet implements BindingInformationProvider {
    private static final ClassName VIEW = ClassName.get("android.view", "View");
    private static final ClassName CONTEXT = ClassName.get("android.content", "Context");
    private static final ClassName UI_THREAD =
            ClassName.get("androidx.annotation", "UiThread");
    private static final ClassName CALL_SUPER =
            ClassName.get("androidx.annotation", "CallSuper");
    private static final ClassName UNBINDER = ClassName.get("me.passin.butterknife.api", "Unbinder");

    private final TypeName targetTypeName;
    private final ClassName bindingClassName;
    private final TypeElement enclosingElement;
    private final boolean isFinal;
    private final boolean isView;
    private final boolean isActivity;
    private final boolean isDialog;
    private final ImmutableList<ViewBinding> viewBindings;
    private final @Nullable BindingInformationProvider parentBinding;

    private BindingSet(
            TypeName targetTypeName, ClassName bindingClassName, TypeElement enclosingElement,
            boolean isFinal, boolean isView, boolean isActivity, boolean isDialog,
            ImmutableList<ViewBinding> viewBindings,
            @Nullable BindingInformationProvider parentBinding) {
        this.isFinal = isFinal;
        this.targetTypeName = targetTypeName;
        this.bindingClassName = bindingClassName;
        this.enclosingElement = enclosingElement;
        this.isView = isView;
        this.isActivity = isActivity;
        this.isDialog = isDialog;
        this.viewBindings = viewBindings;
        this.parentBinding = parentBinding;
    }

    @Override
    public ClassName getBindingClassName() {
        return bindingClassName;
    }

    JavaFile brewJava() {
        // 创建类
        TypeSpec bindingConfiguration = createClass();
        // 创建文件
        return JavaFile.builder(bindingClassName.packageName(), bindingConfiguration)
                // 添加文件顶部注释
                .addFileComment("Generated code from Butter Knife. Do not modify!")
                .build();
    }


    /**
     * public class DemoActivity_ViewBinding implements Unbinder {
     *   private DemoActivity target;
     *
     *   @UiThread
     *   public DemoActivity_ViewBinding(DemoActivity target) {
     *     this(target, target.getWindow().getDecorView());
     *   }
     *
     *   @UiThread
     *   public DemoActivity_ViewBinding(DemoActivity target, View source) {
     *     this.target = target;
     *
     *     target.mFlRoot = (FrameLayout) source.findViewById(2131165267);
     *     target.mTv = (TextView) source.findViewById(2131165344);
     *   }
     *
     *   @Override
     *   @CallSuper
     *   public void unbind() {
     *     DemoActivity target = this.target;
     *     if (target == null) throw new IllegalStateException("Bindings already cleared.");
     *     this.target = null;
     *
     *     target.mFlRoot = null;
     *     target.mTv = null;
     *   }
     * }
     * 先手写一个生成类的具体，然后从上往下一步一步写生成代码。
     */
    private TypeSpec createClass() {
        TypeSpec.Builder result = TypeSpec.classBuilder(bindingClassName.simpleName())
                .addModifiers(PUBLIC)
                // 申明与此文件创建有关的来源元素，在创建文件时使用。
                .addOriginatingElement(enclosingElement);
        if (isFinal) {
            result.addModifiers(FINAL);
        }

        // target 的父类有使用 ButterKnife 绑定，则直接继承父类，父类会实现 Unbinder 接口。
        if (parentBinding != null) {
            result.superclass(parentBinding.getBindingClassName());
        } else {
            // 否则实现 Unbinder 接口，用于解绑视图。
            result.addSuperinterface(UNBINDER);
        }

        // 接着是添加 target 变量。
        result.addField(targetTypeName, "target", PRIVATE);

        // 添加针对 target 对象的构造方法。
        if (isView) {
            result.addMethod(createBindingConstructorForView());
        } else if (isActivity) {
            result.addMethod(createBindingConstructorForActivity());
        } else if (isDialog) {
            result.addMethod(createBindingConstructorForDialog());
        }
        // 最后都会调用该构造函数，并在构造函数中对视图进行绑定。
        result.addMethod(createBindingConstructor());

        // 添加解绑方法，就是对 Unbinder 接口的实现。
        result.addMethod(createBindingUnbindMethod(result));

        return result.build();
    }

    private MethodSpec createBindingConstructorForView() {
        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                .addAnnotation(UI_THREAD)
                .addModifiers(PUBLIC)
                .addParameter(targetTypeName, "target");
        if (constructorNeedsView()) {
            builder.addStatement("this(target, target)");
        } else {
            builder.addStatement("this(target, target.getContext())");
        }
        return builder.build();
    }

    private MethodSpec createBindingConstructorForActivity() {
        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                .addAnnotation(UI_THREAD)
                .addModifiers(PUBLIC)
                .addParameter(targetTypeName, "target");
        if (constructorNeedsView()) {
            builder.addStatement("this(target, target.getWindow().getDecorView())");
        } else {
            builder.addStatement("this(target, target)");
        }
        return builder.build();
    }

    private MethodSpec createBindingConstructorForDialog() {
        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                .addAnnotation(UI_THREAD)
                .addModifiers(PUBLIC)
                .addParameter(targetTypeName, "target");
        if (constructorNeedsView()) {
            builder.addStatement("this(target, target.getWindow().getDecorView())");
        } else {
            builder.addStatement("this(target, target.getContext())");
        }
        return builder.build();
    }

    private MethodSpec createBindingViewDelegateConstructor() {
        return MethodSpec.constructorBuilder()
                .addJavadoc("@deprecated Use {@link #$T($T, $T)} for direct creation.\n    "
                                + "Only present for runtime invocation through {@code ButterKnife.bind()}.\n",
                        bindingClassName, targetTypeName, CONTEXT)
                .addAnnotation(Deprecated.class)
                .addAnnotation(UI_THREAD)
                .addModifiers(PUBLIC)
                .addParameter(targetTypeName, "target")
                .addParameter(VIEW, "source")
                .addStatement(("this(target, source.getContext())"))
                .build();
    }

    private MethodSpec createBindingConstructor() {
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addAnnotation(UI_THREAD)
                .addModifiers(PUBLIC);

        constructor.addParameter(targetTypeName, "target");

        // 由于没有添加绑定资源的功能，因此该方法的返回值都会走 true。
        // 保留判断主要是为了说明编写的思路。
        if (constructorNeedsView()) {
            constructor.addParameter(VIEW, "source");
        } else {
            constructor.addParameter(CONTEXT, "context");
        }

        if (parentBinding != null) {
            if (parentBinding.constructorNeedsView()) {
                constructor.addStatement("super(target, source)");
            } else if (constructorNeedsView()) {
                // 走到这里说明父类绑定了资源，而子类需要绑定视图，因此需要传 source.getContext() 给父视图（和子类生成的构造函数有关）。
                constructor.addStatement("super(target, source.getContext())");
            } else {
                // 走到这里说明父类绑定了资源，而子类也需要绑定资源，因此需要传 context 给父视图（和子类生成的构造函数有关）。
                constructor.addStatement("super(target, context)");
            }
            constructor.addCode("\n");
        }
        // 对 target 赋值。
        constructor.addStatement("this.target = target");
        constructor.addCode("\n");

        if (hasViewBindings()) {
            for (ViewBinding binding : viewBindings) {
                // 添加视图绑定代码。
                addViewBinding(constructor, binding);
            }
        }

        return constructor.build();
    }

    private MethodSpec createBindingUnbindMethod(TypeSpec.Builder bindingClass) {
        MethodSpec.Builder result = MethodSpec.methodBuilder("unbind")
                .addAnnotation(Override.class)
                .addModifiers(PUBLIC);
        if (!isFinal && parentBinding == null) {
            result.addAnnotation(CALL_SUPER);
        }

        result.addStatement("$T target = this.target", targetTypeName);
        result.addStatement("if (target == null) throw new $T($S)", IllegalStateException.class,
                "Bindings already cleared.");
        result.addStatement("this.target = null");
        result.addCode("\n");
        for (ViewBinding binding : viewBindings) {
            result.addStatement("target.$L = null", binding.getSampleName());
        }

        if (parentBinding != null) {
            result.addCode("\n");
            result.addStatement("super.unbind()");
        }
        return result.build();
    }

    private void addViewBinding(MethodSpec.Builder result, ViewBinding binding) {
        // 添加代码块
        CodeBlock.Builder builder = CodeBlock.builder()
                .add("target.$L = ", binding.getSampleName());

        boolean requiresCast = requiresCast(binding.getTypeName());
        if (requiresCast) {
            builder.add("($T) ", binding.getTypeName());
        }
        builder.add("source.findViewById($L)", binding.getId());
        result.addStatement("$L", builder.build());
    }

    /**
     * 当前类需要绑定视图时返回 true。
     */
    private boolean hasViewBindings() {
        return !viewBindings.isEmpty();
    }

    /**
     * 如果此绑定需要视图，则为 true。否则，仅需要上下文 context。
     */
    @Override
    public boolean constructorNeedsView() {
        return hasViewBindings()
                || (parentBinding != null && parentBinding.constructorNeedsView());
    }

    static boolean requiresCast(TypeName type) {
        return !VIEW_TYPE.equals(type.toString());
    }

    @Override
    public String toString() {
        return bindingClassName.toString();
    }

    static Builder newBuilder(TypeElement enclosingElement) {
        return new Builder(enclosingElement);
    }

    static ClassName getBindingClassName(TypeElement typeElement) {
        String packageName = getPackage(typeElement).getQualifiedName().toString();
        String className = typeElement.getQualifiedName().toString().substring(
                packageName.length() + 1).replace('.', '$');
        return ClassName.get(packageName, className + "_ViewBinding");
    }

    static final class Builder {
        private final TypeName targetTypeName;
        private final ClassName bindingClassName;
        private final TypeElement enclosingElement;
        private final boolean isFinal;
        private final boolean isView;
        private final boolean isActivity;
        private final boolean isDialog;

        private @Nullable BindingInformationProvider parentBinding;

        private final Map<Integer, ViewBinding> viewIdMap = new LinkedHashMap<>();

        private Builder(TypeElement element) {
            this.enclosingElement = element;

            // 根据绑定的元素，解析生成文件所需要的信息。
            TypeMirror typeMirror = enclosingElement.asType();

            isView = isSubtypeOfType(typeMirror, VIEW_TYPE);
            isActivity = isSubtypeOfType(typeMirror, ACTIVITY_TYPE);
            isDialog = isSubtypeOfType(typeMirror, DIALOG_TYPE);

            TypeName targetTypeName = TypeName.get(typeMirror);
            if (targetTypeName instanceof ParameterizedTypeName) {
                targetTypeName = ((ParameterizedTypeName) targetTypeName).rawType;
            }
            this.targetTypeName = targetTypeName;

            bindingClassName = getBindingClassName(enclosingElement);
            isFinal = enclosingElement.getModifiers().contains(Modifier.FINAL);
        }


        void addField(int id, ViewBinding binding) {
            viewIdMap.put(id, binding);
        }

        void setParent(BindingInformationProvider parent) {
            this.parentBinding = parent;
        }

        @Nullable
        String findExistingBindingName(int id) {
            ViewBinding viewBinding = viewIdMap.get(id);
            if (viewBinding == null) {
                return null;
            }
            return viewBinding.getSampleName();
        }


        BindingSet build() {
            ImmutableList.Builder<ViewBinding> viewBindings = ImmutableList.builder();
            for (ViewBinding viewBinding : viewIdMap.values()) {
                viewBindings.add(viewBinding);
            }
            return new BindingSet(targetTypeName, bindingClassName, enclosingElement, isFinal, isView,
                    isActivity, isDialog, viewBindings.build(), parentBinding);
        }
    }
}

interface BindingInformationProvider {
    boolean constructorNeedsView();

    ClassName getBindingClassName();
}

final class ClasspathBindingSet implements BindingInformationProvider {
    private boolean constructorNeedsView;
    private ClassName className;

    ClasspathBindingSet(boolean constructorNeedsView, TypeElement classElement) {
        this.constructorNeedsView = constructorNeedsView;
        this.className = BindingSet.getBindingClassName(classElement);
    }

    @Override
    public ClassName getBindingClassName() {
        return className;
    }

    @Override
    public boolean constructorNeedsView() {
        return constructorNeedsView;
    }
}