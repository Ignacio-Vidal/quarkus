package io.quarkus.gradle.tooling;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;

import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.paths.PathCollection;

public class DefaultProjectDescriptor implements Serializable, ProjectDescriptor {

    private static final long serialVersionUID = 1L;

    private WorkspaceModule.Mutable module;

    public DefaultProjectDescriptor(WorkspaceModule.Mutable module) {
        this.module = module;
    }

    @Override
    public WorkspaceModule.Mutable getWorkspaceModule() {
        return module;
    }

    public void setWorkspaceModule(WorkspaceModule.Mutable module) {
        this.module = module;
    }

    @Override
    public WorkspaceModule.Mutable getWorkspaceModuleOrNull(WorkspaceModuleId moduleId) {
        return module.getId().equals(moduleId) ? module : null;
    }

    /**
     * Returns a descriptor holding a copy of the workspace module, which the caller can change without
     * affecting anyone else holding this descriptor.
     * <p>
     * Every application model task of a project is configured with the same workspace module instance. With
     * the configuration cache, each task deserializes its own copy of it. Without the configuration cache,
     * the tasks share the one instance, so a dependency one task adds to the module is seen by the tasks
     * that run after it, which then record it again.
     *
     * @return a descriptor holding a copy of the workspace module
     */
    public DefaultProjectDescriptor copy() {
        return new DefaultProjectDescriptor(copyOf(module));
    }

    /**
     * Copies a workspace module into a new mutable instance.
     * <p>
     * The artifact sources are shared with the original, since nothing changes them once the project is
     * configured. The dependency lists are copied, since those are what the application model tasks add to.
     * {@link WorkspaceModule#mutable()} cannot be used: on a {@link WorkspaceModule.Mutable} it returns the
     * same instance.
     *
     * @param module the module to copy
     * @return a new mutable module with the same content
     */
    static WorkspaceModule.Mutable copyOf(WorkspaceModule module) {
        final WorkspaceModule.Mutable copy = WorkspaceModule.builder().setModuleId(module.getId());
        if (module.getModuleDir() != null) {
            copy.setModuleDir(module.getModuleDir().toPath());
        }
        if (module.getBuildDir() != null) {
            copy.setBuildDir(module.getBuildDir().toPath());
        }
        final PathCollection buildFiles = module.getBuildFiles();
        if (!buildFiles.isEmpty()) {
            final Iterator<Path> i = buildFiles.iterator();
            copy.setBuildFile(i.next());
            if (i.hasNext()) {
                // WorkspaceModule.Mutable can only set one build file, which is all a Gradle project has
                throw new IllegalArgumentException(
                        "Cannot copy workspace module " + module.getId() + " with several build files: " + buildFiles);
            }
        }
        for (String classifier : module.getSourceClassifiers()) {
            copy.addArtifactSources(module.getSources(classifier));
        }
        if (!module.getDirectDependencyConstraints().isEmpty()) {
            copy.setDependencyConstraints(new ArrayList<>(module.getDirectDependencyConstraints()));
        }
        if (!module.getDirectDependencies().isEmpty()) {
            copy.setDependencies(new ArrayList<>(module.getDirectDependencies()));
        }
        if (!module.getTestClasspathDependencyExclusions().isEmpty()) {
            copy.setTestClasspathDependencyExclusions(new ArrayList<>(module.getTestClasspathDependencyExclusions()));
        }
        if (!module.getAdditionalTestClasspathElements().isEmpty()) {
            copy.setAdditionalTestClasspathElements(new ArrayList<>(module.getAdditionalTestClasspathElements()));
        }
        if (module.getParent() != null) {
            copy.setParent(module.getParent());
        }
        return copy;
    }

    @Override
    public String toString() {
        return "DefaultProjectDescriptor{" +
                "\nmodule=" + module +
                "\n}";
    }
}
