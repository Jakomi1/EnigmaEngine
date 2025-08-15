package io.canvasmc.canvas.configuration.writer;

import com.google.common.collect.Lists;
import io.canvasmc.canvas.configuration.jankson.JsonElement;
import io.canvasmc.canvas.configuration.jankson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Util {
    public static @NotNull String multi(String @NotNull [] strings) {
        return strings.length == 1 ? strings[0] : String.join("\n", strings);
    }

    /**
     * Walks recursively throughout the Json Object, if an element is a Json Object, it will check its
     * children *after* the entry consumer has accepted the root
     * @param object the object to walk
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

    public record Diff(List<String> added, List<String> removed) {}

    public static Diff diff(JsonObject oldObj, JsonObject newObj) {
        List<String> added = Lists.newLinkedList();
        List<String> removed = Lists.newLinkedList();

        diffRecursive("", oldObj, newObj, added, removed);

        return new Diff(List.copyOf(added), List.copyOf(removed));
    }

    private static void diffRecursive(String prefix, JsonObject oldObj, JsonObject newObj,
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
}
