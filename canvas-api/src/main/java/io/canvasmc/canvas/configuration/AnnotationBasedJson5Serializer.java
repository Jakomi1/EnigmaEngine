package io.canvasmc.canvas.configuration;

import com.google.common.collect.Lists;
import io.canvasmc.canvas.config.ConfigSerializer;
import io.canvasmc.canvas.config.Configuration;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import io.canvasmc.canvas.configuration.jankson.Jankson;
import io.canvasmc.canvas.configuration.jankson.JsonObject;
import io.canvasmc.canvas.configuration.validator.AnnotationValidator;
import io.canvasmc.canvas.configuration.writer.Util;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Note: 'C' is 'Config', or the configuration class type
@SuppressWarnings("rawtypes")
public record AnnotationBasedJson5Serializer<C>(Configuration definition, Class<C> configClass,
                                                Jankson jankson,
                                                List<AnnotationValidator> validators) implements ConfigSerializer<C> {
    public static final Logger LOGGER = LoggerFactory.getLogger("Json5Serializer");

    public AnnotationBasedJson5Serializer(Class<C> configClass) {
        this(
            Objects.requireNonNull(configClass.getAnnotation(Configuration.class), "Class must contain a Configuration annotation"),
            configClass, Jankson.builder().build(), floodRegistries(AnnotationValidator.class)
        );
    }

    private @NotNull Path getConfigPath() {
        return getConfigFolder().resolve(this.definition.value() + ".json5");
    }

    @Override
    public void write(C config) throws SerializationException {
        // TODO - header writing and top-line comments
        // TODO - validation system actually work please?
        // TODO - make a builder for this
        // TODO - 'post' consumer
        Path configPath = getConfigPath();
        try {
            Files.createDirectories(configPath.getParent());
            // realistically we want to build a diff of the config options
            // and then only serialize the new ones added to the config
            // and remove ones that were removed from the config
            if (!configPath.toFile().exists()) {
                LOGGER.info("Config file doesn't exist, flooding with full config write");
                BufferedWriter writer = Files.newBufferedWriter(configPath);
                writer.write(jankson.toJson(config).toJson(true, true));
                writer.close();
            } else {
                LOGGER.info("Config file exists, building diff");
                try {
                    JsonObject disk = jankson.load(configPath.toFile());
                    JsonObject memory = (JsonObject) jankson.toJson(config);
                    // build a diff to see what 'disk' doesn't have in
                    // comparison to 'memory'
                    Util.Diff diff = Util.diff(disk, memory);
                    if (diff.added().isEmpty() && diff.removed().isEmpty()) {
                        // no diff, no need to write anything
                        LOGGER.info("No diff between disk and memory");
                        return;
                    }

                    // there is a diff, we need to write to disk
                    BufferedWriter writer = Files.newBufferedWriter(configPath);

                    // add the new added keys and their comments
                    for (final String key : diff.added()) {
                        LOGGER.info("Added key '{}' to config file", key);
                        // the key is being added from memory, meaning the comment
                        // is in memory, so we need to read from memory
                        String comment = Util.getCommentByPath(memory, key);
                        Util.putByPath(disk, key, Objects.requireNonNull(Util.getValueByPath(memory, key), "value cannot be null when placing new entry"), comment);
                    }

                    // find the comments from disk and remove them along with the key
                    for (final String key : diff.removed()) {
                        LOGGER.info("Removed key '{}' from config file", key);
                        Util.removeByPath(disk, key);
                    }

                    // write to disk
                    writer.write(Util.cleanMultiLineCommentIndent(disk.toJson(true, true)));
                    writer.close();
                } catch (Throwable e) {
                    throw new RuntimeException("Unable to build diff for config save", e);
                }
            }
        } catch (IOException e) {
            throw new SerializationException(e);
        }
    }

    @Override
    public C read() throws SerializationException {
        Path configPath = getConfigPath();
        if (Files.exists(configPath)) {
            try {
                return jankson.fromJson(jankson.load(getConfigPath().toFile()), configClass);
            } catch (Throwable e) {
                throw new SerializationException(e);
            }
        } else {
            return createDefault();
        }
    }

    @Override
    public C createDefault() {
        return constructUnsafely(configClass);
    }

    private static <T> @NotNull List<T> floodRegistries(Class<T> serviceClass) {
        List<T> services = Lists.newArrayList();
        ServiceLoader<T> loader = ServiceLoader.load(serviceClass);

        for (final T t : loader) {
            LOGGER.info("Loading class {} into registries for {}", t.getClass().getSimpleName(), serviceClass.getName());
            services.add(t);
        }
        return services;
    }
}
