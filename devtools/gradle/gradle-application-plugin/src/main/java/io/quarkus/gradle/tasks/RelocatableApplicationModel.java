package io.quarkus.gradle.tasks;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.quarkus.bootstrap.json.Json;
import io.quarkus.bootstrap.json.JsonArray;
import io.quarkus.bootstrap.json.JsonObject;
import io.quarkus.bootstrap.json.JsonReader;
import io.quarkus.bootstrap.json.JsonString;
import io.quarkus.bootstrap.json.JsonValue;

/**
 * Writes a relocatable rendering of the serialized application model, for use as a build cache input.
 * <p>
 * The serialized model ({@code quarkus-app-model*.dat}) embeds absolute filesystem paths: the module
 * directories of the project itself and the resolved location of every dependency inside the Gradle
 * dependency cache. Consuming that file directly as an {@code @InputFile} makes the cache key of the
 * consuming task a function of the checkout directory and of {@code GRADLE_USER_HOME}, so entries
 * produced in one working directory can never be reused from another. {@code @PathSensitive(RELATIVE)}
 * does not help, because it normalises the location of the input file, not its contents.
 * <p>
 * This class rewrites the model's JSON with every absolute path replaced by a token plus the path
 * relative to the corresponding root, so that byte-identical sources built from different directories
 * produce a byte-identical rendering:
 *
 * <pre>
 * /home/ci/slot-3/app/build/classes  -&gt;  ${project.dir}/build/classes
 * /home/ci/.gradle/caches/foo.jar    -&gt;  ${gradle.user.home}/caches/foo.jar
 * </pre>
 *
 * The result is written next to the model as a separate file and is only ever used to compute cache
 * keys. The model itself keeps its absolute paths, because it is handed to the Quarkus bootstrap
 * as-is — {@code BeforeTestAction} passes it via {@code quarkus-internal.serialized-test-app-model.path}
 * and it is deserialized outside the Gradle plugin, where these tokens would have no meaning.
 */
final class RelocatableApplicationModel {

    private RelocatableApplicationModel() {
    }

    /**
     * A filesystem location that paths in the model are expressed relative to, longest path first so
     * that a root nested inside another wins.
     */
    record Root(String token, Path path) {
    }

    /**
     * Writes the relocatable rendering of {@code model} to {@code target}.
     *
     * @param model the serialized application model to read
     * @param target the file to write the relocatable rendering to
     * @param roots the roots to express absolute paths relative to
     * @throws IOException in case of a failure
     */
    static void write(Path model, Path target, List<Root> roots) throws IOException {
        final List<Root> ordered = new ArrayList<>(roots);
        // a longer root first, so that e.g. the build dir wins over the project dir containing it
        ordered.sort((a, b) -> Integer.compare(b.path().toString().length(), a.path().toString().length()));

        final JsonValue json = JsonReader.of(Files.readString(model)).read();
        if (!(toBuilder(json, ordered) instanceof Json.JsonBuilder<?> builder)) {
            throw new IOException("Expected a JSON object at the root of the application model " + model);
        }
        Files.createDirectories(target.getParent());
        try (Writer writer = Files.newBufferedWriter(target)) {
            builder.appendTo(writer);
        }
    }

    private static Object toBuilder(JsonValue value, List<Root> roots) {
        if (value instanceof JsonObject object) {
            // sorted, so the rendering does not depend on the iteration order of the model's maps
            final Map<String, Object> members = new TreeMap<>();
            for (var member : object.members()) {
                members.put(member.attribute().value(), toBuilder(member.value(), roots));
            }
            final Json.JsonObjectBuilder builder = Json.object(members.size());
            for (Map.Entry<String, Object> member : members.entrySet()) {
                builder.put(member.getKey(), member.getValue());
            }
            return builder;
        }
        if (value instanceof JsonArray array) {
            final Json.JsonArrayBuilder builder = Json.array(array.size());
            array.stream().map(element -> toBuilder(element, roots)).forEach(builder::add);
            return builder;
        }
        if (value instanceof JsonString string) {
            return relocate(string.value(), roots);
        }
        // booleans, numbers and null carry no paths and are rendered as they were read
        return value.toString();
    }

    /**
     * Replaces the longest matching root prefix of {@code value} with its token, leaving values that
     * are not under any root untouched. Separators are normalised so that the rendering is identical
     * across operating systems.
     */
    private static String relocate(String value, Collection<Root> roots) {
        for (Root root : roots) {
            final String prefix = root.path().toString();
            if (value.startsWith(prefix) && (value.length() == prefix.length()
                    || value.charAt(prefix.length()) == '/' || value.charAt(prefix.length()) == '\\')) {
                return root.token() + value.substring(prefix.length()).replace('\\', '/');
            }
        }
        return value;
    }
}
