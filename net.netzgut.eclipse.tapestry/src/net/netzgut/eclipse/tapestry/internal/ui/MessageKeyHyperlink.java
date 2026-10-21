package net.netzgut.eclipse.tapestry.internal.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IType;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.ui.statushandlers.StatusManager;

import net.netzgut.eclipse.tapestry.internal.core.MessageCatalogs;
import net.netzgut.eclipse.tapestry.internal.core.MessageKey;

/// Opens the definition of a message key. The class's own catalog (`Index.properties`) wins over
/// locales and variants; if the key is only in suffixed catalogs, the user chooses.
record MessageKeyHyperlink(HandlerSupport.Search<List<IType>> classes,
                           String key,
                           IRegion region)
    implements
        IHyperlink {

    @Override
    public IRegion getHyperlinkRegion() {
        return this.region;
    }

    @Override
    public String getTypeLabel() {
        return "Tapestry";
    }

    @Override
    public String getHyperlinkText() {
        return "Open Message '" + this.key + "'";
    }

    @Override
    public void open() {
        try {
            List<FileLocation> locations = HandlerSupport.search("Searching message catalogs failed", this::locate);
            if (locations == null) {
                return;
            }

            List<FileLocation> plain = locations.stream().filter(location -> isPlain(location.file())).toList();
            List<FileLocation> targets;
            if (plain.isEmpty()) {
                targets = locations;
            }
            else {
                targets = plain.subList(0, 1);
            }

            String title = getHyperlinkText();
            HandlerSupport.openOrChoose(title,
                                        "Message '" + this.key + "' not found in the catalogs of this class",
                                        targets);
        }
        catch (ExecutionException e) {
            IStatus status = Status.error(e.getMessage(), e.getCause());
            StatusManager.getManager().handle(status, StatusManager.LOG | StatusManager.SHOW);
        }
    }

    private List<FileLocation> locate(IProgressMonitor monitor) throws CoreException {
        Set<IFile> catalogs = new LinkedHashSet<>();
        for (IType type : this.classes.run(monitor)) {
            for (MessageKey.Definition definition : MessageCatalogs.definitions(type, this.key)) {
                catalogs.add(definition.file());
            }
        }

        List<FileLocation> locations = new ArrayList<>();
        for (IFile catalog : catalogs) {
            MessageCatalogs.KeyPosition position = MessageCatalogs.locate(catalog, this.key);
            if (position == null) {
                locations.add(new FileLocation(catalog, 0, 0, 1));
            }
            else {
                locations.add(new FileLocation(catalog, position.offset(), position.length(), position.line()));
            }
        }

        return locations;
    }

    /// `Index.properties` or `com.example.Index.properties`, not `Index_de.properties`.
    private static boolean isPlain(IFile file) {
        String baseName = file.getFullPath().removeFileExtension().lastSegment();
        int lastDot = baseName.lastIndexOf('.');
        return baseName.indexOf('_', lastDot + 1) < 0;
    }
}
