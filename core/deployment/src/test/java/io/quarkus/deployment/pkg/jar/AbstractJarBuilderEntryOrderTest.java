package io.quarkus.deployment.pkg.jar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;

import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.deployment.ApplicationArchiveImpl;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.TransformedClassesBuildItem;
import io.quarkus.deployment.builditem.TransformedClassesBuildItem.TransformedClass;
import io.quarkus.paths.PathTree;

class AbstractJarBuilderEntryOrderTest {

    @TempDir
    Path rootArchive;

    @Test
    void applicationContentIsWrittenInTheSameOrderWhateverTheOrderOfBuildItems() throws IOException {
        List<String> forward = writtenEntries(
                List.of(transformed("org/acme/B.class"), transformed("org/acme/A.class")),
                List.of(generatedClass("org/acme/Gen2"), generatedClass("org/acme/Gen1")),
                List.of(generatedResource("META-INF/z.txt"), generatedResource("META-INF/a.txt")),
                List.of("META-INF/services/org.acme.Second", "META-INF/services/org.acme.First"));
        List<String> reversed = writtenEntries(
                List.of(transformed("org/acme/A.class"), transformed("org/acme/B.class")),
                List.of(generatedClass("org/acme/Gen1"), generatedClass("org/acme/Gen2")),
                List.of(generatedResource("META-INF/a.txt"), generatedResource("META-INF/z.txt")),
                List.of("META-INF/services/org.acme.First", "META-INF/services/org.acme.Second"));

        // transformed classes stay first, since the first entry written for a name wins
        assertThat(forward)
                .containsExactly(
                        "org/acme/A.class", "org/acme/B.class",
                        "org/acme/Gen1.class", "org/acme/Gen2.class",
                        "META-INF/a.txt", "META-INF/z.txt",
                        "META-INF/services/org.acme.First", "META-INF/services/org.acme.Second")
                .isEqualTo(reversed);
    }

    private List<String> writtenEntries(List<TransformedClass> transformedClasses,
            List<GeneratedClassBuildItem> generatedClasses, List<GeneratedResourceBuildItem> generatedResources,
            List<String> concatenatedEntryNames) throws IOException {
        // two jars, so that transformed classes also arrive in the order of the jars
        Map<Path, Set<TransformedClass>> transformedByJar = new LinkedHashMap<>();
        transformedByJar.put(Path.of("first.jar"), new LinkedHashSet<>(List.of(transformedClasses.get(0))));
        transformedByJar.put(Path.of("second.jar"), new LinkedHashSet<>(List.of(transformedClasses.get(1))));
        ApplicationArchivesBuildItem applicationArchives = new ApplicationArchivesBuildItem(
                new ApplicationArchiveImpl(Index.of(new Class<?>[0]), PathTree.ofDirectoryOrArchive(rootArchive).open(), null),
                List.of());
        TestJarBuilder builder = new TestJarBuilder(applicationArchives, new TransformedClassesBuildItem(transformedByJar),
                generatedClasses, generatedResources);

        RecordingArchiveCreator archiveCreator = new RecordingArchiveCreator();
        Map<String, List<byte[]>> concatenatedEntries = new LinkedHashMap<>();
        builder.copyApplicationContent(archiveCreator, concatenatedEntries, name -> false);
        for (String name : concatenatedEntryNames) {
            concatenatedEntries.put(name, List.of(name.getBytes()));
        }
        AbstractJarBuilder.writeConcatenatedEntries(archiveCreator, concatenatedEntries);
        return archiveCreator.entries;
    }

    private static TransformedClass transformed(String fileName) {
        return new TransformedClass(fileName.replace('/', '.').replace(".class", ""), new byte[0], fileName);
    }

    private static GeneratedClassBuildItem generatedClass(String internalName) {
        return new GeneratedClassBuildItem(true, internalName, new byte[0]);
    }

    private static GeneratedResourceBuildItem generatedResource(String name) {
        return new GeneratedResourceBuildItem(name, new byte[0]);
    }

    private static final class TestJarBuilder extends AbstractJarBuilder<SimpleBuildItem> {

        TestJarBuilder(ApplicationArchivesBuildItem applicationArchives, TransformedClassesBuildItem transformedClasses,
                List<GeneratedClassBuildItem> generatedClasses, List<GeneratedResourceBuildItem> generatedResources) {
            super(null, null, null, null, null, applicationArchives, transformedClasses, generatedClasses,
                    generatedResources, List.of(), Set.of(), null, null);
        }

        @Override
        public SimpleBuildItem build() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingArchiveCreator implements ArchiveCreator {

        private final List<String> entries = new ArrayList<>();

        @Override
        public void addManifest(Manifest manifest) {
        }

        @Override
        public void addDirectory(String directory, String source) {
        }

        @Override
        public void addFile(Path origin, String target, String source) {
            entries.add(target);
        }

        @Override
        public void addFile(byte[] bytes, String target, String source) {
            entries.add(target);
        }

        @Override
        public void addFileIfNotExists(Path origin, String target, String source) {
            entries.add(target);
        }

        @Override
        public void addFileIfNotExists(byte[] bytes, String target, String source) {
            entries.add(target);
        }

        @Override
        public void addFile(List<byte[]> bytes, String target, String source) {
            entries.add(target);
        }

        @Override
        public boolean isMultiVersion() {
            return false;
        }

        @Override
        public void makeMultiVersion() {
        }

        @Override
        public void close() {
        }
    }
}
