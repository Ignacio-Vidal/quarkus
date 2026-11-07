package io.quarkus.gradle.tooling;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.model.PlatformImportsImpl;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.gradle.tooling.ApplicationModelFingerprint.Root;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.paths.PathList;

public class ApplicationModelFingerprintTest {

    @TempDir
    Path tempDir;

    @Test
    public void startsWithTheVersionHeader() {
        Checkout checkout = checkout("a");

        assertThat(render(model(checkout, "lib"), checkout)).startsWith(ApplicationModelFingerprint.HEADER + "\n");
    }

    @Test
    public void sameModelInAnotherCheckoutHasTheSameFingerprint() {
        Checkout first = checkout("first");
        Checkout second = checkout("second");

        String firstFingerprint = render(model(first, "lib"), first);

        assertThat(render(model(second, "lib"), second)).isEqualTo(firstFingerprint);
        assertThat(firstFingerprint)
                .doesNotContain(first.rootProjectDir.toString())
                .doesNotContain(first.gradleUserHome.toString())
                .contains("${root-project-dir}/app/build/classes/java/main")
                .contains("${gradle-user-home}/caches/modules-2/files-2.1/org.acme/ext/1.0/ext-1.0.jar");
    }

    @Test
    public void differentDependencyChangesTheFingerprint() {
        Checkout checkout = checkout("a");

        assertThat(render(model(checkout, "other-lib"), checkout)).isNotEqualTo(render(model(checkout, "lib"), checkout));
    }

    @Test
    public void pathUnderNoRootIsKeptAsIs() {
        Checkout checkout = checkout("a");
        Path elsewhere = tempDir.resolve("elsewhere/included.jar").toAbsolutePath();
        ApplicationModel model = builder(checkout, "lib")
                .addDependency(ResolvedDependencyBuilder.newInstance()
                        .setGroupId("org.acme").setArtifactId("included").setVersion("1.0")
                        .setResolvedPath(elsewhere)
                        .setRuntimeCp()
                        .setDeploymentCp())
                .build();

        assertThat(render(model, checkout)).contains(ApplicationModelFingerprintTest.escaped(elsewhere.toString()));
    }

    @Test
    public void nestedRootIsReplacedByItsOwnPlaceholder() {
        // A Gradle user home inside the checkout, as CI systems that cache it between runs often configure
        Path rootProjectDir = tempDir.resolve("checkout").toAbsolutePath();
        Checkout checkout = new Checkout(rootProjectDir, rootProjectDir.resolve(".gradle"));

        assertThat(render(model(checkout, "lib"), checkout))
                .contains("${gradle-user-home}/caches/modules-2")
                .doesNotContain("${root-project-dir}/.gradle");
    }

    @Test
    public void rootOnlyMatchesWholePathSegments() {
        List<Root> roots = List.of(new Root("root", "/ci/build"));

        assertThat(ApplicationModelFingerprint.relocate("/ci/build", roots)).isEqualTo("${root}");
        assertThat(ApplicationModelFingerprint.relocate("/ci/build/app", roots)).isEqualTo("${root}/app");
        assertThat(ApplicationModelFingerprint.relocate("/ci/build-2/app", roots)).isEqualTo("/ci/build-2/app");
        assertThat(ApplicationModelFingerprint.relocate("/ci/buildx", roots)).isEqualTo("/ci/buildx");
    }

    @Test
    public void windowsSeparatorsAreNormalizedUnderARoot() {
        List<Root> roots = List.of(new Root("root", "C:\\ci\\build"));

        assertThat(ApplicationModelFingerprint.relocate("C:\\ci\\build\\app\\lib.jar", roots))
                .isEqualTo("${root}/app/lib.jar");
    }

    @Test
    public void writesTheRenderedFingerprint() throws Exception {
        Checkout checkout = checkout("a");
        ApplicationModel model = model(checkout, "lib");
        Path file = tempDir.resolve("model.fingerprint");

        ApplicationModelFingerprint.write(model, checkout.roots(), file);

        assertThat(Files.readString(file)).isEqualTo(render(model, checkout));
    }

    private Checkout checkout(String name) {
        Path base = tempDir.resolve(name).toAbsolutePath();
        return new Checkout(base.resolve("project"), base.resolve("gradle-home"));
    }

    private static String render(ApplicationModel model, Checkout checkout) {
        return ApplicationModelFingerprint.render(model, checkout.roots());
    }

    private static ApplicationModel model(Checkout checkout, String libName) {
        return builder(checkout, libName).build();
    }

    private static ApplicationModelBuilder builder(Checkout checkout, String libName) {
        Path appDir = checkout.rootProjectDir.resolve("app");
        WorkspaceModule.Mutable module = WorkspaceModule.builder()
                .setModuleId(WorkspaceModuleId.of("org.acme", "app", "1.0"))
                .setModuleDir(appDir)
                .setBuildDir(appDir.resolve("build"))
                .setBuildFile(appDir.resolve("build.gradle.kts"));
        ResolvedDependencyBuilder app = ResolvedDependencyBuilder.newInstance()
                .setGroupId("org.acme").setArtifactId("app").setVersion("1.0")
                .setResolvedPaths(PathList.of(appDir.resolve("build/classes/java/main")))
                .setWorkspaceModule(module);
        return new ApplicationModelBuilder()
                .setAppArtifact(app)
                .setPlatformImports(new PlatformImportsImpl())
                .addDependency(ResolvedDependencyBuilder.newInstance()
                        .setGroupId("org.acme").setArtifactId(libName).setVersion("1.0")
                        .setResolvedPath(checkout.rootProjectDir.resolve(libName + "/build/libs/" + libName + "-1.0.jar"))
                        .setRuntimeCp()
                        .setDeploymentCp()
                        .setDirect(true))
                .addDependency(ResolvedDependencyBuilder.newInstance()
                        .setGroupId("org.acme").setArtifactId("ext").setVersion("1.0")
                        .setResolvedPath(checkout.gradleUserHome
                                .resolve("caches/modules-2/files-2.1/org.acme/ext/1.0/ext-1.0.jar"))
                        .setRuntimeCp()
                        .setDeploymentCp());
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\");
    }

    private record Checkout(Path rootProjectDir, Path gradleUserHome) {
        Map<String, Path> roots() {
            Map<String, Path> roots = new LinkedHashMap<>();
            roots.put("root-project-dir", rootProjectDir);
            roots.put("gradle-user-home", gradleUserHome);
            return roots;
        }
    }
}
