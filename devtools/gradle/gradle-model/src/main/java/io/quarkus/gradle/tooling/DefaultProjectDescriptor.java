package io.quarkus.gradle.tooling;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.io.UncheckedIOException;

import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;

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
     * Returns a deep copy of this descriptor, made by serializing it.
     * <p>
     * Serialization is how Gradle itself copies the descriptor when the configuration cache is enabled, so a task
     * working on a copy sees the same module with and without the configuration cache, and a field added to the
     * workspace module later is copied without a change here.
     *
     * @return a copy sharing no state with this descriptor
     */
    public DefaultProjectDescriptor copy() {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(this);
            }
            final ClassLoader classLoader = DefaultProjectDescriptor.class.getClassLoader();
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
                @Override
                protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
                    try {
                        return Class.forName(desc.getName(), false, classLoader);
                    } catch (ClassNotFoundException e) {
                        return super.resolveClass(desc);
                    }
                }
            }) {
                return (DefaultProjectDescriptor) in.readObject();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to copy the project descriptor", e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Failed to copy the project descriptor", e);
        }
    }

    @Override
    public String toString() {
        return "DefaultProjectDescriptor{" +
                "\nmodule=" + module +
                "\n}";
    }
}
