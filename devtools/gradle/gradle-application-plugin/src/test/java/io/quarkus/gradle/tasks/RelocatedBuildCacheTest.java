package io.quarkus.gradle.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.gradle.testing.BaseGradleTest;
import io.quarkus.gradle.tooling.ToolingUtils;
import io.quarkus.maven.dependency.Dependency;

/**
 * Builds a multi-module project whose Quarkus application depends on a sibling module, in some tests as two copies at
 * different locations sharing one build cache, the way two CI pipelines that each check out into their own directory
 * do.
 */
@ExtendWith(SoftAssertionsExtension.class)
public class RelocatedBuildCacheTest extends BaseGradleTest {

    private static final String MODEL_DIR = "app/build/quarkus/application-model";
    private static final List<String> MODELS = List.of("quarkus-app-model", "quarkus-app-test-model",
            "quarkus-app-model-build");

    @InjectSoftAssertions
    SoftAssertions soft;

    @ParameterizedTest(name = "configuration cache: {0}")
    @ValueSource(booleans = { true, false })
    void outputsAreReusedFromAnotherCheckout(boolean configurationCache) throws Exception {
        Path cache = testProjectDir.resolve("cache");
        Path first = prepareCheckout("first");
        Path second = prepareCheckout("second");

        BuildResult firstBuild = build(first, cache, configurationCache);
        soft.assertThat(taskOutcomes(firstBuild))
                .describedAs("first checkout: %s", firstBuild.getOutput())
                .containsEntry(":app:quarkusAppPartsBuild", TaskOutcome.SUCCESS)
                .containsEntry(":app:test", TaskOutcome.SUCCESS);

        BuildResult secondBuild = build(second, cache, configurationCache);
        soft.assertThat(taskOutcomes(secondBuild))
                .describedAs("second checkout: %s", secondBuild.getOutput())
                .containsEntry(":app:quarkusGenerateCode", TaskOutcome.FROM_CACHE)
                .containsEntry(":app:quarkusGenerateCodeTests", TaskOutcome.FROM_CACHE)
                .containsEntry(":app:compileJava", TaskOutcome.FROM_CACHE)
                .containsEntry(":app:quarkusAppPartsBuild", TaskOutcome.FROM_CACHE)
                .containsEntry(":app:quarkusBuild", TaskOutcome.FROM_CACHE)
                .containsEntry(":app:test", TaskOutcome.FROM_CACHE);

        for (String model : MODELS) {
            // The models themselves differ, as they hold the checkout directory, so this test exercises relocation
            soft.assertThat(Files.readString(modelFile(second, model + ".dat")))
                    .describedAs("%s.dat", model)
                    .isNotEqualTo(Files.readString(modelFile(first, model + ".dat")));
            soft.assertThat(modelFile(second, model + ".fingerprint"))
                    .describedAs("%s.fingerprint", model)
                    .hasSameTextualContentAs(modelFile(first, model + ".fingerprint"));
        }

        // Restored from the first checkout's build, so it must not point into the first checkout
        Properties artifactProperties = new Properties();
        try (Reader reader = Files.newBufferedReader(second.resolve("app/build/quarkus-artifact.properties"))) {
            artifactProperties.load(reader);
        }
        soft.assertThat(artifactProperties.getProperty("metadata.library-dir"))
                .describedAs("library directory in the second checkout's quarkus-artifact.properties")
                .isEqualTo(Path.of("quarkus-app", "lib").toString());
    }

    @Test
    void newDependencyInvalidatesTheReusedOutputs() throws Exception {
        Path cache = testProjectDir.resolve("cache");
        Path first = prepareCheckout("first");
        Path second = prepareCheckout("second");
        build(first, cache, true);
        addDependency(second, "io.quarkus:quarkus-jackson");

        BuildResult result = build(second, cache, true);

        soft.assertThat(taskOutcomes(result))
                .describedAs("second checkout, with a new dependency: %s", result.getOutput())
                .containsEntry(":app:quarkusGenerateCode", TaskOutcome.SUCCESS)
                .containsEntry(":app:quarkusAppPartsBuild", TaskOutcome.SUCCESS)
                .containsEntry(":app:quarkusBuild", TaskOutcome.SUCCESS)
                .containsEntry(":app:test", TaskOutcome.SUCCESS);
        soft.assertThat(Files.readString(modelFile(second, "quarkus-app-model-build.fingerprint")))
                .contains("quarkus-jackson")
                .isNotEqualTo(Files.readString(modelFile(first, "quarkus-app-model-build.fingerprint")));
    }

    @Test
    void siblingModuleIsRecordedOnceWithoutTheConfigurationCache() throws Exception {
        Path checkout = prepareCheckout("checkout");

        // All the model tasks in one build, which without the configuration cache share one workspace module
        BuildResult result = runner(checkout, testProjectDir.resolve("cache"), false,
                ":app:quarkusGenerateTestAppModel", ":app:quarkusGenerateAppModel", ":app:quarkusBuildAppModel")
                .build();

        for (String model : MODELS) {
            ApplicationModel appModel = ToolingUtils.deserializeAppModel(modelFile(checkout, model + ".dat"));
            List<String> directDependencies = appModel.getAppArtifact().getWorkspaceModule().getDirectDependencies()
                    .stream().map(Dependency::getArtifactId).toList();
            soft.assertThat(directDependencies)
                    .describedAs("direct dependencies in %s.dat: %s", model, result.getOutput())
                    .containsOnlyOnce("common");
        }
    }

    @Test
    void testModelRecordsTheMainOutputDirectories() throws Exception {
        Path checkout = prepareCheckout("checkout");

        // The test model on its own, which used to run before the main classes and resources existed
        BuildResult result = runner(checkout, testProjectDir.resolve("cache"), true, ":app:quarkusGenerateTestAppModel")
                .build();

        ApplicationModel appModel = ToolingUtils.deserializeAppModel(modelFile(checkout, "quarkus-app-test-model.dat"));
        // Gradle records the canonical location, which on macOS differs from the temporary directory's
        Path appBuildDir = checkout.toRealPath().resolve("app/build");
        soft.assertThat(appModel.getAppArtifact().getResolvedPaths())
                .describedAs("resolved paths of the application in the test model: %s", result.getOutput())
                .contains(appBuildDir.resolve("classes/java/main"), appBuildDir.resolve("resources/main"));
    }

    @Test
    void mainModelRecordsTheMainResources() throws Exception {
        Path checkout = prepareCheckout("checkout");

        BuildResult result = runner(checkout, testProjectDir.resolve("cache"), true, ":app:quarkusGenerateAppModel")
                .build();

        ApplicationModel appModel = ToolingUtils.deserializeAppModel(modelFile(checkout, "quarkus-app-model.dat"));
        soft.assertThat(appModel.getAppArtifact().getResolvedPaths())
                .describedAs("resolved paths of the application in the main model: %s", result.getOutput())
                .contains(checkout.toRealPath().resolve("app/build/resources/main"));
    }

    @Test
    void quarkusBuildIsReusedWhenTheApplicationPartsComeFromTheCache() throws Exception {
        Path cache = testProjectDir.resolve("cache");
        Path checkout = prepareCheckout("checkout");
        runner(checkout, cache, true, ":app:quarkusBuild").build();
        runner(checkout, cache, true, "clean").build();

        // quarkusAppPartsBuild restored from the cache leaves its scratch directory empty, which used to be an input
        BuildResult result = runner(checkout, cache, true, ":app:quarkusBuild").build();

        soft.assertThat(taskOutcomes(result))
                .describedAs("after a clean: %s", result.getOutput())
                .containsEntry(":app:quarkusAppPartsBuild", TaskOutcome.FROM_CACHE)
                .containsEntry(":app:quarkusBuild", TaskOutcome.FROM_CACHE);
    }

    @Test
    void changedSiblingModuleReachesTheBuiltApplication() throws Exception {
        Path cache = testProjectDir.resolve("cache");
        Path checkout = prepareCheckout("checkout");
        runner(checkout, cache, true, ":app:quarkusBuild").build();
        // Changes the content of the sibling's jar, but not the application model
        replaceIn(checkout.resolve("common/src/main/java/org/acme/common/Greeting.java"), "\"hello\"", "\"bonjour\"");

        BuildResult result = runner(checkout, cache, true, ":app:quarkusBuild").build();

        soft.assertThat(taskOutcomes(result))
                .describedAs("after changing the sibling module: %s", result.getOutput())
                .containsEntry(":app:quarkusBuild", TaskOutcome.SUCCESS);
        soft.assertThat(classInJar(checkout.resolve("app/build/quarkus-app/lib/main"), "org/acme/common/Greeting.class"))
                .describedAs("Greeting.class in the built application")
                .contains("bonjour");
    }

    @ParameterizedTest(name = "configuration cache: {0}")
    @ValueSource(booleans = { true, false })
    void outputsAreReusedWithAMavenLocalRepositoryAtAnotherLocation(boolean configurationCache) throws Exception {
        Path cache = testProjectDir.resolve("cache");
        Path first = prepareCheckout("first");
        Path second = prepareCheckout("second");
        // The Quarkus artifacts stay where they are; only the extra artifact moves with the Maven local repository
        String quarkusArtifacts = "maven { url = uri(\"" + portable(realMavenLocalRepository()) + "\") }";
        for (Path checkout : List.of(first, second)) {
            replaceIn(checkout.resolve("app/build.gradle.kts"), "// ADDITIONAL_REPOSITORIES", quarkusArtifacts);
            replaceIn(checkout.resolve("app/build.gradle.kts"), "// ADDITIONAL_DEPENDENCIES",
                    "implementation(\"org.acme:extra:1.0\")");
        }
        Path firstRepository = installExtraArtifact(testProjectDir.resolve("first-m2"));
        Path secondRepository = installExtraArtifact(testProjectDir.resolve("second-m2"));

        BuildResult firstBuild = runner(first, cache, configurationCache, "build",
                "-Dmaven.repo.local=" + firstRepository).build();
        BuildResult secondBuild = runner(second, cache, configurationCache, "build",
                "-Dmaven.repo.local=" + secondRepository).build();

        soft.assertThat(taskOutcomes(secondBuild))
                .describedAs("second checkout: %s", secondBuild.getOutput())
                .containsEntry(":app:quarkusGenerateCode", TaskOutcome.FROM_CACHE)
                .containsEntry(":app:quarkusAppPartsBuild", TaskOutcome.FROM_CACHE)
                .containsEntry(":app:quarkusBuild", TaskOutcome.FROM_CACHE)
                .containsEntry(":app:test", TaskOutcome.FROM_CACHE);
        String fingerprint = Files.readString(modelFile(second, "quarkus-app-model-build.fingerprint"));
        soft.assertThat(fingerprint)
                .describedAs("fingerprint of the second checkout's model, first checkout: %s", firstBuild.getOutput())
                .contains("${maven-local-repository}/org/acme/extra/1.0/extra-1.0.jar")
                .doesNotContain(portable(secondRepository.toRealPath()))
                .isEqualTo(Files.readString(modelFile(first, "quarkus-app-model-build.fingerprint")));
    }

    @Test
    void fileDependencyOutsideTheProjectIsNotReusedFromAnotherCheckout() throws Exception {
        Path cache = testProjectDir.resolve("cache");
        Path first = prepareCheckout("first");
        Path second = prepareCheckout("second");
        for (Path checkout : List.of(first, second)) {
            // The same jar, outside each checkout
            Path jar = writeExtraJar(testProjectDir.resolve(checkout.getFileName() + "-libs").resolve("extra.jar"));
            replaceIn(checkout.resolve("app/build.gradle.kts"), "// ADDITIONAL_DEPENDENCIES",
                    "implementation(files(\"" + portable(jar) + "\"))");
        }

        assertMissesInTheSecondCheckout(first, second, cache, "second-libs");
    }

    @Test
    void includedBuildOutsideTheProjectIsNotReusedFromAnotherCheckout() throws Exception {
        Path cache = testProjectDir.resolve("cache");
        Path first = prepareCheckout("first");
        Path second = prepareCheckout("second");
        for (Path checkout : List.of(first, second)) {
            // The sibling module moves to a build of its own, next to each checkout. It stays a subproject there:
            // depending on the root project of an included build fails to resolve the deployment classpath.
            Path included = testProjectDir.resolve(checkout.getFileName() + "-included");
            FileUtils.moveDirectory(checkout.resolve("common").toFile(), included.resolve("common").toFile());
            Files.writeString(included.resolve("settings.gradle.kts"),
                    "rootProject.name = \"included\"\ninclude(\"common\")\n");
            Path commonBuildFile = included.resolve("common/build.gradle.kts");
            Files.writeString(commonBuildFile,
                    Files.readString(commonBuildFile) + "\ngroup = \"org.acme\"\nversion = \"1.0\"\n");
            replaceIn(checkout.resolve("settings.gradle.kts"), "include(\"common\", \"app\")",
                    "include(\"app\")\nincludeBuild(\"" + portable(included) + "\")");
            replaceIn(checkout.resolve("app/build.gradle.kts"), "implementation(project(\":common\"))",
                    "implementation(\"org.acme:common:1.0\")");
        }

        assertMissesInTheSecondCheckout(first, second, cache, "second-included");
    }

    /**
     * Builds both checkouts, the second one after deleting the first, and expects the second to rebuild the
     * application instead of restoring outputs that were built from the first checkout's files.
     */
    private void assertMissesInTheSecondCheckout(Path first, Path second, Path cache, String recordedLocation)
            throws IOException {
        build(first, cache, true);
        FileUtils.deleteDirectory(first.toFile());

        BuildResult result = build(second, cache, true);

        soft.assertThat(taskOutcomes(result))
                .describedAs("second checkout: %s", result.getOutput())
                .containsEntry(":app:quarkusGenerateCode", TaskOutcome.SUCCESS)
                .containsEntry(":app:quarkusAppPartsBuild", TaskOutcome.SUCCESS)
                .containsEntry(":app:quarkusBuild", TaskOutcome.SUCCESS)
                .containsEntry(":app:test", TaskOutcome.SUCCESS);
        soft.assertThat(Files.readString(modelFile(second, "quarkus-app-model-build.fingerprint")))
                .describedAs("fingerprint of the second checkout's model")
                .contains(recordedLocation);
    }

    private BuildResult build(Path checkout, Path cache, boolean configurationCache) {
        return runner(checkout, cache, configurationCache, "build").build();
    }

    private static GradleRunner runner(Path checkout, Path cache, boolean configurationCache, String... tasks) {
        List<String> args = new ArrayList<>(List.of(tasks));
        args.add("--build-cache");
        args.add("--stacktrace");
        args.add(configurationCache ? CONFIGURATION_CACHE : NO_CONFIGURATION_CACHE);
        args.add("-PsharedBuildCache=" + cache.toAbsolutePath());
        // With CI set, quarkusBuild is not stored in the cache by default
        Map<String, String> env = new HashMap<>(System.getenv());
        env.remove("CI");
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(checkout.toFile())
                .withEnvironment(env)
                .withArguments(args);
    }

    private Path prepareCheckout(String name) throws IOException, URISyntaxException {
        URL url = getClass().getClassLoader().getResource("io/quarkus/gradle/tasks/caching/relocation");
        Path checkout = testProjectDir.resolve(name);
        FileUtils.copyDirectory(new File(url.toURI()), checkout.toFile());
        FileUtils.copyFile(new File("../gradle.properties"), checkout.resolve("gradle.properties").toFile());
        return checkout;
    }

    private static void addDependency(Path checkout, String coordinates) throws IOException {
        replaceIn(checkout.resolve("app/build.gradle.kts"), "// ADDITIONAL_DEPENDENCIES",
                "implementation(\"" + coordinates + "\")");
    }

    private static void replaceIn(Path file, String target, String replacement) throws IOException {
        String content = Files.readString(file);
        assertThat(content).describedAs("%s", file).contains(target);
        Files.writeString(file, content.replace(target, replacement));
    }

    /**
     * The Maven local repository the builds would otherwise use, where the Quarkus artifacts under test are installed.
     */
    private static Path realMavenLocalRepository() {
        String repository = System.getProperty("maven.repo.local");
        return repository != null ? Path.of(repository)
                : Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    private static Path installExtraArtifact(Path repository) throws IOException {
        Path dir = repository.resolve("org/acme/extra/1.0");
        writeExtraJar(dir.resolve("extra-1.0.jar"));
        Files.writeString(dir.resolve("extra-1.0.pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId>
                  <artifactId>extra</artifactId>
                  <version>1.0</version>
                </project>
                """);
        return repository;
    }

    /**
     * Writes the same bytes wherever it is written, so that only the location differs between checkouts.
     */
    private static Path writeExtraJar(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            JarEntry entry = new JarEntry(JarFile.MANIFEST_NAME);
            entry.setTime(0);
            out.putNextEntry(entry);
            manifest.write(out);
            out.closeEntry();
        }
        return jar;
    }

    private static String classInJar(Path dir, String entryName) throws IOException {
        try (Stream<Path> jars = Files.list(dir)) {
            for (Path jar : jars.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                try (JarFile jarFile = new JarFile(jar.toFile())) {
                    JarEntry entry = jarFile.getJarEntry(entryName);
                    if (entry != null) {
                        return new String(jarFile.getInputStream(entry).readAllBytes(), StandardCharsets.ISO_8859_1);
                    }
                }
            }
        }
        throw new AssertionError("no jar in " + dir + " contains " + entryName);
    }

    /**
     * A path as it can be written in a Kotlin string literal, and as the fingerprint records it.
     */
    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static Path modelFile(Path checkout, String name) {
        return checkout.resolve(MODEL_DIR).resolve(name);
    }
}
