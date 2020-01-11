package me.paasin.butterknife.compiler;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;

/**
 * @author: zbb
 * @date: 2020/1/9 10:13
 * @desc:
 */
public class Utils {

    private static final String NULLABLE_ANNOTATION_NAME = "Nullable";

    public static boolean isFieldRequired(Element element) {
        return !hasAnnotationWithName(element, NULLABLE_ANNOTATION_NAME);
    }

    public static boolean hasAnnotationWithName(Element element, String simpleName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            String annotationName = mirror.getAnnotationType().asElement().getSimpleName().toString();
            if (simpleName.equals(annotationName)) {
                return true;
            }
        }
        return false;
    }


}
