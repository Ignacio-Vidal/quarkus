package io.quarkus.gradle.tooling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.MappableCollectionFactory;

/**
 * A rendering of a serialized application model in which the absolute paths of well-known directories are
 * replaced by placeholders, for use as a build cache key.
 * <p>
 * The serialized application model records the absolute location of every artifact, so hashing it ties a task's
 * cache key to the checkout directory and to the Gradle user home. Consumers of the model hash this fingerprint
 * instead, so a build in another directory, or with another Gradle user home, can reuse their cached outputs.
 * <p>
 * The fingerprint has one line per value of the model, in the order the model is serialized, with map keys sorted,
 * so that it can be compared with a text diff when two builds unexpectedly produce different keys. It holds
 * everything in the model except the location of those directories: a path under none of them is kept as is, which
 * can only cost a cache miss, never produce a wrong cache hit.
 */
public final class ApplicationModelFingerprint {

    /**
     * The first line of every fingerprint. Change it whenever the rendering changes, so that fingerprints written
     * by an earlier version never match the ones written by a later one.
     */
    static final String HEADER = "# Quarkus application model fingerprint, version 1";

    private ApplicationModelFingerprint() {
    }

    /**
     * Writes the fingerprint of a model to a file.
     *
     * @param model the application model
     * @param roots the directories to replace, keyed by placeholder name
     * @param file the file to write
     * @throws IOException if the file cannot be written
     */
    public static void write(ApplicationModel model, Map<String, Path> roots, Path file) throws IOException {
        Files.writeString(file, render(model, roots), StandardCharsets.UTF_8);
    }

    /**
     * Renders the fingerprint of a model.
     *
     * @param model the application model
     * @param roots the directories to replace, keyed by placeholder name
     * @return the fingerprint
     */
    public static String render(ApplicationModel model, Map<String, Path> roots) {
        final List<Root> sortedRoots = new ArrayList<>(roots.size());
        roots.forEach((name, dir) -> {
            if (dir != null) {
                sortedRoots.add(new Root(name, dir.toString()));
            }
        });
        // the longest first, so that a directory inside another one, such as a Gradle user home inside the
        // checkout, is replaced by its own placeholder
        sortedRoots.sort(Comparator.comparingInt((Root r) -> r.path.length()).reversed()
                .thenComparing(r -> r.name));

        final StringBuilder out = new StringBuilder();
        out.append(HEADER).append('\n');
        render("", model.asMap(MappableCollectionFactory.defaultInstance()), sortedRoots, out);
        return out.toString();
    }

    private static void render(String pointer, Object value, List<Root> roots, StringBuilder out) {
        if (value instanceof Map<?, ?> map) {
            // sorted, since the model holds some of its maps as HashMaps
            final Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            if (sorted.isEmpty()) {
                out.append(pointer).append("={}\n");
            }
            sorted.forEach((k, v) -> render(pointer + "/" + k, v, roots, out));
        } else if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                out.append(pointer).append("=[]\n");
            }
            int i = 0;
            for (Object element : collection) {
                render(pointer + "[" + i++ + "]", element, roots, out);
            }
        } else {
            out.append(pointer).append('=');
            if (value instanceof String s) {
                appendEscaped(relocate(s, roots), out);
            } else {
                out.append(value);
            }
            out.append('\n');
        }
    }

    static String relocate(String value, List<Root> roots) {
        for (Root root : roots) {
            if (value.startsWith(root.path)) {
                if (value.length() == root.path.length()) {
                    return root.placeholder();
                }
                final char next = value.charAt(root.path.length());
                if (next == '/' || next == '\\') {
                    // the separator is normalized, so that the same relative location renders the same way on any OS
                    return root.placeholder() + value.substring(root.path.length()).replace('\\', '/');
                }
            }
        }
        return value;
    }

    private static void appendEscaped(String value, StringBuilder out) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
    }

    record Root(String name, String path) {
        String placeholder() {
            return "${" + name + "}";
        }
    }
}
