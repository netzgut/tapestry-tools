package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceProxy;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

/// The override folders in all open Java projects. Finding them means walking every source folder
/// of the workspace, so the result is cached, and a resource change listener drops it when an
/// override folder, a project or a `.classpath` (source folders) appears, disappears or changes.
public final class OverrideFolders {

    private record Cached(List<String> names, List<IContainer> folders) {
    }

    private static volatile Cached cached;

    /// Registered by the bundle activator for the lifetime of the bundle.
    public static final IResourceChangeListener INVALIDATOR = OverrideFolders::resourceChanged;

    public static List<IContainer> all() throws CoreException {
        List<String> names = TapestryPreferences.overrideFolders();
        Cached current = cached;
        if (current != null
            && current.names().equals(names)
            && current.folders().stream().allMatch(IResource::exists)) {
            return current.folders();
        }
        List<IContainer> scanned = scan(names);
        List<IContainer> folders = List.copyOf(scanned);
        cached = new Cached(names, folders);
        return folders;
    }

    static void invalidate() {
        cached = null;
    }

    private static List<IContainer> scan(List<String> names) throws CoreException {
        List<IContainer> result = new ArrayList<>();
        if (names.isEmpty()) {
            return result;
        }

        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!project.isOpen() || !project.hasNature(JavaCore.NATURE_ID)) {
                continue;
            }

            IJavaProject javaProject = JavaCore.create(project);
            for (IContainer root : RelatedFiles.sourceFolders(javaProject)) {
                // Proxy visitors avoid creating IResource objects for every file and are much faster
                root.accept((IResourceProxy proxy) -> {
                    if (proxy.getType() == IResource.FOLDER && names.contains(proxy.getName())) {
                        IContainer folder = (IContainer) proxy.requestResource();
                        result.add(folder);
                        return false;
                    }

                    return !proxy.isDerived();
                }, IResource.NONE);
            }
        }

        return result;
    }

    private static void resourceChanged(IResourceChangeEvent event) {
        IResourceDelta delta = event.getDelta();
        if (delta == null || cached == null) {
            return;
        }

        List<String> names = cached.names();
        try {
            delta.accept(child -> {
                if (cached == null) {
                    return false; // already invalidated, stop visiting
                }

                IResource resource = child.getResource();
                boolean addedOrRemoved =
                    child.getKind() == IResourceDelta.ADDED || child.getKind() == IResourceDelta.REMOVED;
                switch (resource.getType()) {
                    case IResource.PROJECT -> {
                        if (addedOrRemoved || (child.getFlags() & IResourceDelta.OPEN) != 0) {
                            invalidate();
                        }
                    }
                    case IResource.FOLDER -> {
                        if (addedOrRemoved && names.contains(resource.getName())) {
                            invalidate();
                        }
                    }
                    case IResource.FILE -> {
                        if (resource.getName().equals(".classpath")) {
                            invalidate();
                        }
                    }
                }
                return true;
            });
        }
        catch (CoreException _) {
            invalidate();
        }
    }

    private OverrideFolders() {
    }
}
