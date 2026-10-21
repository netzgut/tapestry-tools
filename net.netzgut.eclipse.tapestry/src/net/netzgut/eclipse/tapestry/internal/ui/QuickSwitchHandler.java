package net.netzgut.eclipse.tapestry.internal.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IType;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.statushandlers.StatusManager;

import net.netzgut.eclipse.tapestry.internal.core.NewResource;
import net.netzgut.eclipse.tapestry.internal.core.RelatedElements;

/// Opens a popup listing the class, templates, messages, scripts, … of the current page/component,
/// followed by the template and message catalog it doesn't have yet, which are created when chosen.
public class QuickSwitchHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        Object source = HandlerSupport.findSource(part, selection, _ -> true);
        if (source == null) {
            HandlerSupport.showStatus(part, "Not a Tapestry class or file");
            return null;
        }

        List<Object> related =
            HandlerSupport.search("Searching for related Tapestry files failed", monitor -> collect(source, monitor));
        if (related == null) {
            return null; // cancelled
        }

        String name;
        if (source instanceof IType type) {
            name = type.getElementName();
        }
        else {
            name = ((IFile) source).getName();
        }

        if (related.isEmpty()) {
            HandlerSupport.showStatus(part, "No related files found for " + name);
            return null;
        }

        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
        Shell shell = HandlerUtil.getActiveShellChecked(event);
        new QuickSwitchPopup(shell,
                             related,
                             "Related to " + name + " · Enter to open",
                             target -> open(page, target)).open();

        return null;
    }

    private static List<Object> collect(Object source, IProgressMonitor monitor) throws CoreException {
        List<Object> related = RelatedElements.collect(source, monitor);
        List<Object> elements = new ArrayList<>(related);
        IType owner;
        if (source instanceof IType type) {
            owner = type;
        }
        else {
            owner = elements.stream() //
                            .filter(IType.class::isInstance) //
                            .map(IType.class::cast) //
                            .findFirst() //
                            .orElse(null);
        }

        if (owner != null) {
            for (NewResource.Kind kind : NewResource.missing(owner)) {
                IFile location = NewResource.location(owner, kind);
                elements.add(new NewResourceEntry(owner, kind, location));
            }
        }

        return elements;
    }

    private static void open(IWorkbenchPage page, Object target) {
        if (!(target instanceof NewResourceEntry entry)) {
            HandlerSupport.open(page, target);
            return;
        }

        try {
            HandlerSupport.createAndOpen(page, entry.type(), entry.kind());
        }
        catch (ExecutionException e) {
            IStatus status = Status.error(e.getMessage(), e.getCause());
            StatusManager.getManager().handle(status, StatusManager.LOG | StatusManager.SHOW);
        }
    }
}
