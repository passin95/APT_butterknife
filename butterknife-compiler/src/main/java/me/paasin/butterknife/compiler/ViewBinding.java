package me.paasin.butterknife.compiler;

import com.squareup.javapoet.TypeName;

final class ViewBinding {
    private final int id;
    private final String sampleName;
    private final TypeName typeName;
    private final boolean required;

    public ViewBinding(int id, String sampleName, TypeName typeName, boolean required) {
        this.id = id;
        this.sampleName = sampleName;
        this.typeName = typeName;
        this.required = required;
    }

    public int getId() {
        return id;
    }

    public String getSampleName() {
        return sampleName;
    }

    public TypeName getTypeName() {
        return typeName;
    }

    public boolean isRequired() {
        return required;
    }

}
