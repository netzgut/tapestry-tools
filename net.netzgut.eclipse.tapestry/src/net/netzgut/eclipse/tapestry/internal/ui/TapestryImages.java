package net.netzgut.eclipse.tapestry.internal.ui;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.ResourceLocator;
import org.eclipse.swt.graphics.Image;

/// Images used from code. SWT images are OS resources that must be disposed; the JFace image
/// registry owns them and disposes them with the display, so we never create [Image]s ourselves. On
/// HiDPI screens JFace picks `tapestry-logo@2x.png` next to the requested file automatically.
final class TapestryImages {

    private static final String LOGO = "net.netzgut.eclipse.tapestry.logo";

    private TapestryImages() {
    }

    /// Must be called on the UI thread, like all registry access.
    static Image logo() {
        ImageRegistry registry = JFaceResources.getImageRegistry();
        Image image = registry.get(LOGO);
        if (image == null) {
            ImageDescriptor descriptor =
                ResourceLocator.imageDescriptorFromBundle(TapestryImages.class, "icons/tapestry-logo.png")
                               .orElse(ImageDescriptor.getMissingImageDescriptor());
            registry.put(LOGO, descriptor);
            image = registry.get(LOGO);
        }

        return image;
    }
}
