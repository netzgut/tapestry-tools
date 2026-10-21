package net.netzgut.eclipse.tapestry.internal.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IType;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import net.netzgut.eclipse.tapestry.internal.core.NewResource;
import net.netzgut.eclipse.tapestry.internal.core.RelatedFiles;
import net.netzgut.eclipse.tapestry.internal.core.SwitchTargets;
import net.netzgut.eclipse.tapestry.internal.core.TemplateLocator;

/// Switches between a Tapestry class and its template. Works on the active editor, or on the
/// selection when a view (e.g. Package Explorer) is active.
public class SwitchHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        Object source = HandlerSupport.findSource(part, selection, RelatedFiles::isTemplate);
        if (source == null) {
            HandlerSupport.showStatus(part, "Not a Tapestry class or template");
            return null;
        }

        List<Object> targets = HandlerSupport.search("Searching for Tapestry counterpart failed", monitor -> {
            List<Object> found = new ArrayList<>();
            if (source instanceof IType type) {
                List<IFile> templates = TemplateLocator.findTemplates(type);
                found.addAll(templates);
            }
            else {
                List<IType> classes = TemplateLocator.findClasses((IFile) source, monitor);
                found.addAll(classes);
            }

            return found;
        });

        if (targets == null) {
            return null; // cancelled
        }

        if (targets.isEmpty()) {
            if (source instanceof IType type && offerTemplate(event, type)) {
                return null;
            }

            String message;
            if (source instanceof IType type) {
                message = "No template found for " + type.getElementName();
            }
            else {
                message = "No class found for " + ((IFile) source).getName();
            }

            HandlerSupport.showStatus(part, message);
            return null;
        }

        Shell shell = HandlerUtil.getActiveShellChecked(event);
        Object target = chooseTarget(shell, source, targets);
        if (target != null) {
            IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
            HandlerSupport.open(page, target);
        }

        return null;
    }

    /// Asks whether to create the missing template of a page or component, and opens it. False if
    /// the class doesn't get templates.
    private static boolean offerTemplate(ExecutionEvent event, IType type) throws ExecutionException {
        IFile file;
        try {
            if (!NewResource.isApplicable(type, NewResource.Kind.TEMPLATE)) {
                return false;
            }
            file = NewResource.location(type, NewResource.Kind.TEMPLATE);
        }
        catch (CoreException e) {
            throw new ExecutionException("Locating template failed", e);
        }

        Shell shell = HandlerUtil.getActiveShellChecked(event);
        String question = type.getElementName()
                          + " has no template. Create "
                          + file.getFullPath().makeRelative()
                          + "?";
        boolean create = MessageDialog.openQuestion(shell, "Create Template", question);
        if (!create) {
            return true;
        }

        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
        HandlerSupport.createAndOpen(page, type, NewResource.Kind.TEMPLATE);

        return true;
    }

    private static Object chooseTarget(Shell shell, Object source, List<Object> targets) {
        if (targets.size() == 1) {
            return targets.get(0);
        }

        if (source instanceof IType type) {
            List<IFile> templates = targets.stream().map(IFile.class::cast).toList();
            IFile preferred =
                SwitchTargets.preferredTemplate(templates, type.getElementName(), type.getFullyQualifiedName());
            if (preferred != null) {
                return preferred;
            }
        }

        return HandlerSupport.choose(shell, "Switch Between Class and Template", targets);
    }
}
