package net.netzgut.eclipse.tapestry.internal;

import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.ResourcesPlugin;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import net.netzgut.eclipse.tapestry.internal.core.OverrideFolders;
import net.netzgut.eclipse.tapestry.internal.core.TemplateValidation;

/// Bundle lifecycle. With `Bundle-ActivationPolicy: lazy`, OSGi starts the bundle when the first of
/// its classes is loaded (a handler, the completion computer, …), so this runs before any cache is
/// filled; [#stop] undoes it when Eclipse shuts down or the bundle is updated.
public class Activator implements BundleActivator {

    @Override
    public void start(BundleContext context) {
        ResourcesPlugin.getWorkspace().addResourceChangeListener(OverrideFolders.INVALIDATOR,
                                                                 IResourceChangeEvent.POST_CHANGE);
        TemplateValidation.INSTANCE.start();
    }

    @Override
    public void stop(BundleContext context) {
        TemplateValidation.INSTANCE.stop();
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(OverrideFolders.INVALIDATOR);
    }
}
