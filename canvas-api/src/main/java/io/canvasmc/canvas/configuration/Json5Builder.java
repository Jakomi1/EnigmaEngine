package io.canvasmc.canvas.configuration;

import io.canvasmc.canvas.configuration.jankson.Jankson;
import java.util.function.Consumer;
import java.util.function.Function;

public class Json5Builder<T> {
    private Class<T> classOf;
    private Consumer<PostContext<T>> postInit;
    private String header;
    private Function<Jankson.Builder, Jankson.Builder> func = (a) -> a;

    public Json5Builder<T> classOf(Class<T> clazz) {
        this.classOf = clazz;
        return this;
    }

    public Json5Builder<T> post(Consumer<PostContext<T>> post) {
        this.postInit = post;
        return this;
    }

    public Json5Builder<T> hook(Function<Jankson.Builder, Jankson.Builder> func) {
        this.func = func;
        return this;
    }

    public Json5Builder<T> header(String header) {
        this.header = header;
        return this;
    }

    public AnnotationBasedJson5Serializer<T> build() {
        return new AnnotationBasedJson5Serializer<>(
            classOf, postInit, header, func
        );
    }

    public record PostContext<C>(C configuration, String contents) {
    }
}
