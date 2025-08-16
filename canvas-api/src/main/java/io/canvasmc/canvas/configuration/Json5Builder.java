package io.canvasmc.canvas.configuration;

import java.util.function.Consumer;

public class Json5Builder<T> {
    private Class<T> classOf;
    private Consumer<PostContext<T>> postInit;
    private String[] header;

    public Json5Builder<T> classOf(Class<T> clazz) {
        this.classOf = clazz;
        return this;
    }

    public Json5Builder<T> post(Consumer<PostContext<T>> post) {
        this.postInit = post;
        return this;
    }

    public AnnotationBasedJson5Serializer<T> build() {
        return new AnnotationBasedJson5Serializer<>(
            classOf, postInit, header
        );
    }

    public Json5Builder<T> header(String[] header) {
        this.header = header;
        return this;
    }

    public record PostContext<C>(C configuration, String contents) {
    }
}
