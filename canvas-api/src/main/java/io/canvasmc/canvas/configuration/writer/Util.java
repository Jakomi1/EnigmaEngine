package io.canvasmc.canvas.configuration.writer;

import com.google.common.collect.Lists;
import io.canvasmc.canvas.configuration.TriConsumer;
import io.canvasmc.canvas.configuration.jankson.JsonArray;
import io.canvasmc.canvas.configuration.jankson.JsonElement;
import io.canvasmc.canvas.configuration.jankson.JsonNull;
import io.canvasmc.canvas.configuration.jankson.JsonObject;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.canvasmc.canvas.configuration.jankson.JsonPrimitive;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Util {
    public static @NotNull String multi(String @NotNull [] strings) {
        return strings.length == 1 ? strings[0] : String.join("\n", strings);
    }

    /**
     * Walks recursively throughout the Json Object, if an element is a Json Object, it will check its
     * children *after* the entry consumer has accepted the root
     *
     * @param object       the object to walk
     * @param forEachEntry the entry consumer
     */
    public static void walk(@NotNull JsonObject object, BiConsumer<JsonElement, String> forEachEntry, String root) {
        object.forEach((name, element) -> {
            String path = root + name;
            forEachEntry.accept(element, path);

            if (element instanceof JsonObject jsonObject) {
                walk(jsonObject, forEachEntry, path + ".");
            }
        });
    }

    public static boolean isNestedWithin(Class<?> root, Class<?> candidate) {
        if (root == null || candidate == null) return false;

        for (Class<?> inner : root.getDeclaredClasses()) {
            if (inner.equals(candidate)) {
                return true;
            }
            if (isNestedWithin(inner, candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Looks up the comment associated with a dot-separated path inside a JsonObject.
     *
     * @param root The root JsonObject
     * @param path The dot-separated key path (e.g. "ah.test.inner")
     * @return The comment String, or null if not found
     */
    public static @Nullable String getCommentByPath(@NotNull JsonObject root, @NotNull String path) {
        String[] parts = path.split("\\.");
        JsonObject current = root;

        for (int i = 0; i < parts.length; i++) {
            String key = parts[i];

            // if we're at the last part, return the comment directly
            if (i == parts.length - 1) {
                return current.getComment(key);
            }

            JsonElement child = current.get(key);
            if (!(child instanceof JsonObject childObj)) {
                return null; // broken path?
            }

            current = childObj;
        }

        return null;
    }

    public static void compileMappings(Class<?> clazz, JsonObject root, String pathPrefix, TriConsumer<JsonElement, String, Field> consumer) {
        for (final Field field : clazz.getFields()) {
            // TODO - validate class before read/write and kill if it's invalid
            if (field.accessFlags().contains(AccessFlag.STATIC)) continue; // don't want static
            if (field.accessFlags().contains(AccessFlag.FINAL)) continue; // don't want final
            if (!field.accessFlags().contains(AccessFlag.PUBLIC)) continue; // must be public
            String fieldName = field.getName();
            String path = pathPrefix.isEmpty() ? fieldName : pathPrefix + "." + fieldName;

            Class<?> classType = field.getType();

            if (Util.isNestedWithin(clazz, classType)) {
                compileMappings(classType, root, path, consumer);
            } else {
                JsonElement value = Util.getValueByPath(root, path);
                if (value != null) {
                    consumer.accept(value, path, field);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void migrate(Map<String, Object> yamlMap, JsonObject janksonObject) {
        for (final Map.Entry<String, Object> entry : yamlMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                // nested object
                JsonObject child = janksonObject.getObject(key);
                if (child == null) {
                    child = new JsonObject();
                    janksonObject.put(key, child);
                }
                migrate((Map<String, Object>) value, child);
            } else if (value instanceof List list) {
                JsonArray array = new JsonArray();
                for (final Object o : list) {
                    array.add(convertElement(o));
                }
                janksonObject.put(key, array);
            } else {
                janksonObject.put(key, convertElement(value));
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonElement convertElement(Object o) {
        if (o == null) return JsonNull.INSTANCE;
        if (o instanceof String
        || o instanceof Boolean || o instanceof Number) return new JsonPrimitive(o);
        if (o instanceof Map) {
            JsonObject jsonObject = new JsonObject();
            migrate((Map<String, Object>) o, jsonObject);
            return jsonObject;
        }
        if (o instanceof List list) {
            JsonArray array = new JsonArray();
            for (final Object object : list) {
                array.add(convertElement(object));
            }
            return array;
        }
        return new JsonPrimitive(o.toString());
    }

    public static @Nullable JsonElement getValueByPath(@NotNull JsonObject root, @NotNull String path) {
        String[] parts = path.split("\\.");
        JsonObject current = root;

        for (int i = 0; i < parts.length; i++) {
            String key = parts[i];

            // if we're at the last part, return the value directly
            if (i == parts.length - 1) {
                return current.get(key);
            }

            JsonElement child = current.get(key);
            if (!(child instanceof JsonObject childObj)) {
                return null; // broken path?
            }

            current = childObj;
        }

        return null;
    }

    public static void removeByPath(@NotNull JsonObject root, @NotNull String path) {
        String[] parts = path.split("\\.");
        JsonObject current = root;

        for (int i = 0; i < parts.length; i++) {
            String key = parts[i];

            if (i == parts.length - 1) {
                current.remove(key);
                return;
            }

            JsonElement child = current.get(key);
            if (!(child instanceof JsonObject childObj)) {
                return; // broken path?
            }

            current = childObj;
        }

    }

    public static void putByPath(@NotNull JsonObject root, @NotNull String path, @NotNull JsonElement value, final String comment) {
        String[] parts = path.split("\\.");
        JsonObject current = root;

        for (int i = 0; i < parts.length; i++) {
            String key = parts[i];

            if (i == parts.length - 1) {
                current.put(key, value, comment);
                return;
            }

            JsonElement child = current.get(key);
            if (!(child instanceof JsonObject)) {
                current.put(key, new JsonObject());
                child = current.get(key);
            }

            if (child == null) throw new RuntimeException("Nested object was null when traversing");
            current = (JsonObject) child;
        }
    }

    // Note: i=0 == head, i=1 == body
    public static String[] splitHeader(String json5) {
        String[] result = new String[2];
        StringBuilder header = new StringBuilder();
        StringBuilder body = new StringBuilder();

        boolean inHeader = true;
        boolean inBlockComment = false;
        boolean inLineComment = false;

        for (int i = 0; i < json5.length(); i++) {
            char c = json5.charAt(i);
            char next = (i + 1 < json5.length()) ? json5.charAt(i + 1) : '\0';

            if (inHeader) {
                if (!inBlockComment && !inLineComment && c == '/' && next == '*') {
                    inBlockComment = true;
                    header.append(c).append(next);
                    i++;
                    continue;
                }
                if (inBlockComment && c == '*' && next == '/') {
                    inBlockComment = false;
                    header.append(c).append(next);
                    i++;
                    continue;
                }
                if (!inBlockComment && !inLineComment && c == '/' && next == '/') {
                    inLineComment = true;
                    header.append(c).append(next);
                    i++;
                    continue;
                }
                // Line comment end
                if (inLineComment && (c == '\n' || c == '\r')) {
                    inLineComment = false;
                    header.append(c);
                    if (c != '\n') {
                        header.append('\n');
                    }
                    continue;
                }

                if (inLineComment || inBlockComment) {
                    header.append(c);
                } else if (Character.isWhitespace(c)) {
                    header.append(c);
                } else {
                    inHeader = false;
                    body.append(c);
                }
            } else {
                body.append(c);
            }
        }

        header.append('\n');

        result[0] = header.toString().trim();
        result[1] = body.toString().trim();
        return result;
    }

    /**
     * Cleans doubled indentation in multi-line comments while preserving relative indents.
     *
     * @param json5 The JSON5 string
     * @return A new string with normalized multi-line comment indentation
     */
    public static @NotNull String cleanMultiLineCommentIndent(String json5) {
        Pattern multiLinePattern = Pattern.compile("/\\*([\\s\\S]*?)\\*/");
        Matcher matcher = multiLinePattern.matcher(json5);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String commentBlock = matcher.group(0);

            // split into multi-line
            String[] lines = commentBlock.split("\n", -1);
            if (lines.length <= 1) {
                // single-line comment
                matcher.appendReplacement(sb, Matcher.quoteReplacement(commentBlock));
                continue;
            }

            // get base indent from the first line
            String firstLine = lines[0];
            String baseIndent = firstLine.replaceFirst("^(\\s*).*", "$1");

            // remove extra global indent from all lines
            for (int i = 1; i < lines.length; i++) {
                lines[i] = lines[i].replaceFirst("^\\s{0," + baseIndent.length() + "}", "");
                lines[i] = baseIndent + lines[i]; // prepend base indent
            }

            commentBlock = String.join("\n", lines);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(commentBlock));
        }

        matcher.appendTail(sb);
        return sb.toString();
    }

    public static @NotNull Diff diff(JsonObject oldObj, JsonObject newObj) {
        List<String> added = Lists.newLinkedList();
        List<String> removed = Lists.newLinkedList();

        diffRecursive("", oldObj, newObj, added, removed);

        return new Diff(List.copyOf(added), List.copyOf(removed));
    }

    private static void diffRecursive(String prefix, @NotNull JsonObject oldObj, @NotNull JsonObject newObj,
                                      List<String> added, List<String> removed) {
        Set<String> oldKeys = oldObj.keySet();
        Set<String> newKeys = newObj.keySet();

        for (String key : newKeys) {
            if (!oldKeys.contains(key)) {
                added.add(prefix + key);
            } else {
                JsonElement oldVal = oldObj.get(key);
                JsonElement newVal = newObj.get(key);

                if (oldVal instanceof JsonObject && newVal instanceof JsonObject) {
                    diffRecursive(prefix + key + ".", (JsonObject) oldVal, (JsonObject) newVal, added, removed);
                }
            }
        }

        for (String key : oldKeys) {
            if (!newKeys.contains(key)) {
                removed.add(prefix + key);
            }
        }
    }

    public record Diff(List<String> added, List<String> removed) {
    }
}
