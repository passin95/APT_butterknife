package me.paasin.butterknife.compiler;

import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeName;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import me.passin.butterknife.annotations.BindView;

import static javax.lang.model.element.ElementKind.CLASS;
import static javax.lang.model.element.ElementKind.INTERFACE;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;

public final class ButterKnifeProcessor extends AbstractProcessor {
    protected Filer mFiler;
    protected Messager mMessager;
    protected Types mTypes;
    protected Elements mElements;
    static final String VIEW_TYPE = "android.view.View";
    static final String ACTIVITY_TYPE = "android.app.Activity";
    static final String DIALOG_TYPE = "android.app.Dialog";

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        mFiler = processingEnv.getFiler();
        mMessager = processingEnv.getMessager();
        mTypes = processingEnv.getTypeUtils();
        mElements = processingEnv.getElementUtils();
    }

    @Override
    public Set<String> getSupportedOptions() {
        // 支持的选项（key）。
//        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
//        builder.add("HOST");
//        return builder.build();
        // 可在模块内的 build.gradle 配置，并在 init() 中通过 processingEnvironment.getOptions().get("HOST") 拿到具体的值。
        // android {
        //     defaultConfig {
        //        javaCompileOptions {
        //            annotationProcessorOptions {
        //                arguments = ["HOST": "component"]
        //            }
        //        }
        //    }
        //
        return super.getSupportedOptions();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        // 支持的注解。
        Set<String> types = new LinkedHashSet<>();
        for (Class<? extends Annotation> annotation : getSupportedAnnotations()) {
            types.add(annotation.getCanonicalName());
        }
        return types;
    }

    private Set<Class<? extends Annotation>> getSupportedAnnotations() {
        Set<Class<? extends Annotation>> annotations = new LinkedHashSet<>();
        annotations.add(BindView.class);
        return annotations;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        // 支持的 Java 版本号。
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment env) {
        Map<TypeElement, BindingSet> bindingMap = findAndParseTargets(env);

        for (Map.Entry<TypeElement, BindingSet> entry : bindingMap.entrySet()) {
            TypeElement typeElement = entry.getKey();
            BindingSet binding = entry.getValue();

            JavaFile javaFile = binding.brewJava();
            try {
                javaFile.writeTo(mFiler);
            } catch (IOException e) {
                error(typeElement, "Unable to write binding for type %s: %s", typeElement, e.getMessage());
            }
        }

        // 如果返回 true，则不会传递给后续处理器进行处理; 如果返回 false，则注释类型是无人认领的，后续的处理器可能会继续处理它们。
        return false;
    }

    private Map<TypeElement, BindingSet> findAndParseTargets(RoundEnvironment env) {
        // 一个类会有多次 BindView 的使用，因此需要有一个对应关系。
        // key 为被注解元素所在的类。
        // value 之所以是构造类，是为了处理父类有绑定的情况。
        Map<TypeElement, BindingSet.Builder> builderMap = new LinkedHashMap();
        // 所有被注解元素所在的类。
        Set<TypeElement> bindingTargetElements = new LinkedHashSet<>();

        // getElementsAnnotatedWith 可以拿到所有添加 @BindView 注解的元素。
        for (Element element : env.getElementsAnnotatedWith(BindView.class)) {
            try {
                // 解析元素信息并配置绑定类生成需要的信息。
                parseBindView(element, builderMap, bindingTargetElements);
            } catch (Exception e) {
                logParsingError(element, BindView.class, e);
            }
        }

        // 从 bindingTargetElements 中筛选出父类也是被注解元素所在的类。
        // key 为被注解元素所在的类且需要继承父类。
        // value 为父类的要求（是否需要传递参数 view）以及父类的类名。
        Map<TypeElement, ClasspathBindingSet> classpathBindings = findAllSupertypeBindings(builderMap, bindingTargetElements);

        Deque<Map.Entry<TypeElement, BindingSet.Builder>> entries = new ArrayDeque<>(builderMap.entrySet());
        Map<TypeElement, BindingSet> bindingMap = new LinkedHashMap<>();
        while (!entries.isEmpty()) {
            Map.Entry<TypeElement, BindingSet.Builder> entry = entries.removeFirst();

            TypeElement type = entry.getKey();
            BindingSet.Builder builder = entry.getValue();

            TypeElement parentType = findParentType(type, bindingTargetElements, classpathBindings.keySet());
            if (parentType == null) {
                bindingMap.put(type, builder.build());
            } else {
                BindingInformationProvider parentBinding = bindingMap.get(parentType);
                if (parentBinding == null) {
                    parentBinding = classpathBindings.get(parentType);
                }
                if (parentBinding != null) {
                    builder.setParent(parentBinding);
                    bindingMap.put(type, builder.build());
                } else {
                    // 具有超类绑定，但我们尚未构建它。重新排队以便稍后使用。
                    entries.addLast(entry);
                }
            }
        }

        return bindingMap;
    }


    private void parseBindView(Element element, Map<TypeElement, BindingSet.Builder> builderMap,
                               Set<TypeElement> bindingTargetElements) {
        // element：被注解元素。
        // enclosingElement：被注解元素的父元素，它是一个类，例如 Activity。
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

        // 首先验证 element 是否满足框架所定义的基本限制。
        boolean hasError = isInaccessibleViaGeneratedCode(BindView.class, "fields", element)
                || isBindingInWrongPackage(BindView.class, element);

        TypeMirror elementType = element.asType();
        if (elementType.getKind() == TypeKind.TYPEVAR) {
            TypeVariable typeVariable = (TypeVariable) elementType;
            elementType = typeVariable.getUpperBound();
        }
        Name qualifiedName = enclosingElement.getQualifiedName();
        Name simpleName = element.getSimpleName();
        // 验证 element 是否是 View 的子类。
        if (!isSubtypeOfType(elementType, VIEW_TYPE) && !isInterface(elementType)) {
            if (elementType.getKind() == TypeKind.ERROR) {
                note(element, "@%s field with unresolved type (%s) "
                                + "must elsewhere be generated as a View or interface. (%s.%s)",
                        BindView.class.getSimpleName(), elementType, qualifiedName, simpleName);
            } else {
                error(element, "@%s fields must extend from View or be an interface. (%s.%s)",
                        BindView.class.getSimpleName(), qualifiedName, simpleName);
                hasError = true;
            }
        }

        if (hasError) {
            return;
        }

        // 获取元素上注解的值。
        int id = element.getAnnotation(BindView.class).value();
        BindingSet.Builder builder = builderMap.get(enclosingElement);
        if (builder != null) {
            String existingBindingName = builder.findExistingBindingName(id);
            // 出现同一个 id 绑定了多次视图，则打印错误且不会尝试多次绑定。
            if (existingBindingName != null) {
                error(element, "Attempt to use @%s for an already bound ID %d on '%s'. (%s.%s)",
                        BindView.class.getSimpleName(), id, existingBindingName,
                        enclosingElement.getQualifiedName(), element.getSimpleName());
                return;
            }
        } else {
            builder = getOrCreateBindingBuilder(builderMap, enclosingElement);
        }

        String sampleName = simpleName.toString();
        TypeName typeName = TypeName.get(elementType);
        boolean required = Utils.isFieldRequired(element);
        builder.addField(id, new ViewBinding(id, sampleName, typeName, required));

        bindingTargetElements.add(enclosingElement);
    }


    private boolean isInaccessibleViaGeneratedCode(Class<? extends Annotation> annotationClass,
                                                   String targetThing, Element element) {
        boolean hasError = false;
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

        // 验证字段或方法的修饰符。
        Set<Modifier> modifiers = element.getModifiers();
        // 不应该被 private 和 static 关键字修饰。
        if (modifiers.contains(PRIVATE) || modifiers.contains(STATIC)) {
            error(element, "@%s %s must not be private or static. (%s.%s)",
                    annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
                    element.getSimpleName());
            hasError = true;
        }

        // 验证注解元素的父元素类型，它应该是一个类。
        if (enclosingElement.getKind() != CLASS) {
            error(enclosingElement, "@%s %s may only be contained in classes. (%s.%s)",
                    annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
                    element.getSimpleName());
            hasError = true;
        }

        // 不应该是私有的。
        if (enclosingElement.getModifiers().contains(PRIVATE)) {
            error(enclosingElement, "@%s %s may not be contained in private classes. (%s.%s)",
                    annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
                    element.getSimpleName());
            hasError = true;
        }

        return hasError;
    }


    private boolean isBindingInWrongPackage(Class<? extends Annotation> annotationClass,
                                            Element element) {
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
        String qualifiedName = enclosingElement.getQualifiedName().toString();

        if (qualifiedName.startsWith("android.")) {
            error(element, "@%s-annotated class incorrectly in Android framework package. (%s)",
                    annotationClass.getSimpleName(), qualifiedName);
            return true;
        }
        if (qualifiedName.startsWith("java.")) {
            error(element, "@%s-annotated class incorrectly in Java framework package. (%s)",
                    annotationClass.getSimpleName(), qualifiedName);
            return true;
        }

        return false;
    }

    static boolean isSubtypeOfType(TypeMirror typeMirror, String otherType) {
        if (otherType.equals(typeMirror.toString())) {
            return true;
        }
        if (typeMirror.getKind() != TypeKind.DECLARED) {
            return false;
        }
        DeclaredType declaredType = (DeclaredType) typeMirror;
        List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
        if (typeArguments.size() > 0) {
            StringBuilder typeString = new StringBuilder(declaredType.asElement().toString());
            typeString.append('<');
            for (int i = 0; i < typeArguments.size(); i++) {
                if (i > 0) {
                    typeString.append(',');
                }
                typeString.append('?');
            }
            typeString.append('>');
            if (typeString.toString().equals(otherType)) {
                return true;
            }
        }
        Element element = declaredType.asElement();
        if (!(element instanceof TypeElement)) {
            return false;
        }
        TypeElement typeElement = (TypeElement) element;
        TypeMirror superType = typeElement.getSuperclass();
        if (isSubtypeOfType(superType, otherType)) {
            return true;
        }
        for (TypeMirror interfaceType : typeElement.getInterfaces()) {
            if (isSubtypeOfType(interfaceType, otherType)) {
                return true;
            }
        }
        return false;
    }


    private Map<TypeElement, ClasspathBindingSet> findAllSupertypeBindings(
            Map<TypeElement, BindingSet.Builder> builderMap, Set<TypeElement> processedInThisRound) {
        Map<TypeElement, ClasspathBindingSet> classpathBindings = new HashMap<>();
        // 获取所有支持的注解。
        Set<Class<? extends Annotation>> supportedAnnotations = getSupportedAnnotations();
        // 所有需要在构造函数传参 View 的注解。
        Set<Class<? extends Annotation>> requireViewInConstructor =
                ImmutableSet.<Class<? extends Annotation>>builder().add(BindView.class).build();
        // 剩下的注解只需要向父类传参 context。
        supportedAnnotations.removeAll(requireViewInConstructor);

        // typeElement 为被注解元素所在的类。
        for (TypeElement typeElement : builderMap.keySet()) {
            // 确保在子类之前处理超类，因为父类的构造函数参数需要子类提供。
            // superClasses 存储着需要继承父类的类元素。
            Deque<TypeElement> superClasses = new ArrayDeque<>();
            TypeElement superClass = getSuperClass(typeElement);
            while (superClass != null && !processedInThisRound.contains(superClass)
                    && !classpathBindings.containsKey(superClass)) {
                // 逐级添加存在绑定的父类到队列的头部，直至最终的父类。
                // 这样能保证在有多重继承关系的类之间，父类一定在子类的前面。
                superClasses.addFirst(superClass);
                superClass = getSuperClass(superClass);
            }

            // 标记着父类的构造函数是否需要参数 View。
            boolean parentHasConstructorWithView = false;
            while (!superClasses.isEmpty()) {
                TypeElement superclass = superClasses.removeFirst();
                // 查找 superclass 的父类的要求以及类名。
                ClasspathBindingSet classpathBinding =
                        findBindingInfoForType(superclass, requireViewInConstructor, supportedAnnotations,
                                parentHasConstructorWithView);
                if (classpathBinding != null) {
                    // superclass 父类的要求，它的所有子类也必须要提供。
                    parentHasConstructorWithView |= classpathBinding.constructorNeedsView();
                    classpathBindings.put(superclass, classpathBinding);
                }
            }
        }
        return ImmutableMap.copyOf(classpathBindings);
    }

    private ClasspathBindingSet findBindingInfoForType(
            TypeElement typeElement, Set<Class<? extends Annotation>> requireConstructorWithView,
            Set<Class<? extends Annotation>> otherAnnotations, boolean needsConstructorWithView) {
        boolean foundSupportedAnnotation = false;
        for (Element enclosedElement : typeElement.getEnclosedElements()) {
            for (Class<? extends Annotation> bindViewAnnotation : requireConstructorWithView) {
                if (enclosedElement.getAnnotation(bindViewAnnotation) != null) {
                    return new ClasspathBindingSet(true, typeElement);
                }
            }
            for (Class<? extends Annotation> supportedAnnotation : otherAnnotations) {
                if (enclosedElement.getAnnotation(supportedAnnotation) != null) {
                    if (needsConstructorWithView) {
                        return new ClasspathBindingSet(true, typeElement);
                    }
                    foundSupportedAnnotation = true;
                }
            }
        }
        if (foundSupportedAnnotation) {
            return new ClasspathBindingSet(false, typeElement);
        } else {
            return null;
        }
    }

    private BindingSet.Builder getOrCreateBindingBuilder(
            Map<TypeElement, BindingSet.Builder> builderMap, TypeElement enclosingElement) {
        BindingSet.Builder builder = builderMap.get(enclosingElement);
        if (builder == null) {
            builder = BindingSet.newBuilder(enclosingElement);
            builderMap.put(enclosingElement, builder);
        }
        return builder;
    }

    private void logParsingError(Element element, Class<? extends Annotation> annotation,
                                 Exception e) {
        StringWriter stackTrace = new StringWriter();
        e.printStackTrace(new PrintWriter(stackTrace));
        error(element, "Unable to parse @%s binding.\n\n%s", annotation.getSimpleName(), stackTrace);
    }

    @Nullable
    private TypeElement findParentType(TypeElement typeElement, Set<TypeElement> parents, Set<TypeElement> classpathParents) {
        while (true) {
            typeElement = getSuperClass(typeElement);
            if (typeElement == null || parents.contains(typeElement)
                    || classpathParents.contains(typeElement)) {
                return typeElement;
            }
        }
    }

    private boolean isInterface(TypeMirror typeMirror) {
        return typeMirror instanceof DeclaredType
                && ((DeclaredType) typeMirror).asElement().getKind() == INTERFACE;
    }

    @Nullable
    private TypeElement getSuperClass(TypeElement typeElement) {
        TypeMirror type = typeElement.getSuperclass();
        if (type.getKind() == TypeKind.NONE) {
            return null;
        }
        return (TypeElement) ((DeclaredType) type).asElement();
    }

    private void note(Element element, String message, Object... args) {
        printMessage(Diagnostic.Kind.NOTE, element, message, args);
    }

    private void error(Element element, String message, Object... args) {
        printMessage(Diagnostic.Kind.ERROR, element, message, args);
    }

    private void printMessage(Diagnostic.Kind kind, Element element, String message, Object[] args) {
        if (args.length > 0) {
            message = String.format(message, args);
        }

        processingEnv.getMessager().printMessage(kind, message, element);
    }
}
