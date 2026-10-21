package net.netzgut.eclipse.tapestry.internal.ui;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.function.Predicate;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IOrdinaryClassFile;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.statushandlers.StatusManager;
import org.eclipse.ui.texteditor.ITextEditor;

import net.netzgut.eclipse.tapestry.internal.core.NewResource;

/// What the switch commands and hyperlinks have in common.
final class HandlerSupport {

    /// A search that may use the Java index and therefore must not run on the UI thread.
    @FunctionalInterface
    interface Search<T> {

        T run(IProgressMonitor monitor) throws CoreException;
    }

    private static final int STATUS_DURATION_MS = 5_000;

    /// Identifies the status message shown last, so clearing an older one leaves it alone.
    private static Object statusToken;

    private HandlerSupport() {
    }

    /// The class (as [IType]) or file (as [IFile], if accepted by `fileFilter`) in the active
    /// editor, or selected in the active view (e.g. Package Explorer); `null` if there is none.
    static Object findSource(IWorkbenchPart part, ISelection selection, Predicate<IFile> fileFilter) {
        if (part instanceof IEditorPart editor) {
            IEditorInput input = editor.getEditorInput();
            IJavaElement javaElement = JavaUI.getEditorInputJavaElement(input);
            IType type = toType(javaElement);
            if (type != null) {
                return type;
            }

            IFile file = input.getAdapter(IFile.class);
            return accept(file, fileFilter);
        }

        if (selection instanceof IStructuredSelection structured && structured.size() == 1) {
            Object element = structured.getFirstElement();
            IJavaElement javaElement = Adapters.adapt(element, IJavaElement.class);
            IType type = toType(javaElement);
            if (type != null) {
                return type;
            }

            IFile file = Adapters.adapt(element, IFile.class);
            return accept(file, fileFilter);
        }
        return null;
    }

    /// Runs `search` in a background thread while the UI shows a busy cursor (and a progress dialog
    /// if it takes long). Returns `null` if the user cancelled.
    static <T> T search(String failureMessage, Search<T> search) throws ExecutionException {
        Object[] result = new Object[1];
        try {
            PlatformUI.getWorkbench().getProgressService().busyCursorWhile(monitor -> {
                try {
                    result[0] = search.run(monitor);
                }
                catch (CoreException e) {
                    throw new InvocationTargetException(e);
                }
            });
        }
        catch (InvocationTargetException e) {
            throw new ExecutionException(failureMessage, e.getCause());
        }
        catch (InterruptedException e) {
            return null;
        }

        @SuppressWarnings("unchecked")
        T typed = (T) result[0];
        return typed;
    }

    /// Lets the user pick one of several targets; `null` if cancelled.
    static Object choose(Shell shell, String title, List<?> targets) {
        TargetLabelProvider labels = new TargetLabelProvider();
        try {
            ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell, labels);
            dialog.setTitle(title);
            dialog.setMessage("Several candidates found. Select the one to open:");
            Object[] elements = targets.toArray();
            dialog.setElements(elements);
            dialog.setMultipleSelection(false);
            int returnCode = dialog.open();
            if (returnCode != Window.OK) {
                return null;
            }

            return dialog.getFirstResult();
        }
        finally {
            labels.dispose();
        }
    }

    /// Opens the single target, lets the user choose between several, or reports that there
    /// are none.
    static void openOrChoose(String title, String notFoundMessage, List<?> targets) {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null) {
            return;
        }

        IWorkbenchPage page = window.getActivePage();
        if (targets.isEmpty()) {
            showStatus(page.getActivePart(), notFoundMessage);
            return;
        }

        Object target;
        if (targets.size() == 1) {
            target = targets.get(0);
        }
        else {
            Shell shell = window.getShell();
            target = choose(shell, title, targets);
        }

        if (target != null) {
            open(page, target);
        }
    }

    /// Creates the template or message catalog of `type` and opens it.
    static void createAndOpen(IWorkbenchPage page, IType type, NewResource.Kind kind) throws ExecutionException {
        IFile created = search("Creating " + type.getElementName() + "." + kind.extension + " failed", monitor -> {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IFile file = NewResource.location(type, kind);
            ISchedulingRule rule = workspace.getRuleFactory().createRule(file);
            IFile[] result = new IFile[1];
            workspace.run(inner -> result[0] = NewResource.create(type, kind, inner),
                          rule,
                          IWorkspace.AVOID_UPDATE,
                          monitor);
            return result[0];
        });

        if (created != null) {
            open(page, created);
        }
    }

    static void open(IWorkbenchPage page, Object target) {
        try {
            if (target instanceof IFile file) {
                IDE.openEditor(page, file);
            }
            else if (target instanceof FileLocation location) {
                IFile file = location.file();
                IEditorPart editor = IDE.openEditor(page, file);
                // Multi-page editors (e.g. WTP's XML editor with its Design tab) adapt to their text page
                ITextEditor text = Adapters.adapt(editor, ITextEditor.class);
                if (text != null) {
                    text.selectAndReveal(location.offset(), location.length());
                }
            }
            else if (target instanceof IJavaElement element) {
                JavaUI.openInEditor(element);
            }
        }
        catch (CoreException e) {
            IStatus status = Status.error("Could not open " + target, e);
            StatusManager.getManager().handle(status, StatusManager.LOG | StatusManager.SHOW);
        }
    }

    /// Shows `message` as an error in the part's status line until the selection changes or a few
    /// seconds pass, whichever comes first; a newer message isn't cleared.
    static void showStatus(IWorkbenchPart part, String message) {
        IStatusLineManager statusLine = null;
        if (part != null && part.getSite() instanceof IEditorSite site) {
            statusLine = site.getActionBars().getStatusLineManager();
        }
        else if (part != null && part.getSite() instanceof IViewSite site) {
            statusLine = site.getActionBars().getStatusLineManager();
        }

        Display display = PlatformUI.getWorkbench().getDisplay();
        if (statusLine != null) {
            statusLine.setErrorMessage(message);
            Object token = new Object();
            statusToken = token;
            IStatusLineManager manager = statusLine;
            ISelectionProvider selections = part.getSite().getSelectionProvider();
            ISelectionChangedListener[] listener = new ISelectionChangedListener[1];

            Runnable clear = () -> {
                if (selections != null) {
                    selections.removeSelectionChangedListener(listener[0]);
                }
                if (statusToken == token) {
                    statusToken = null;
                    manager.setErrorMessage(null);
                }
            };

            listener[0] = _ -> clear.run();
            if (selections != null) {
                selections.addSelectionChangedListener(listener[0]);
            }

            display.timerExec(STATUS_DURATION_MS, clear);
        }

        display.beep();
    }

    private static IType toType(IJavaElement element) {
        if (element == null) {
            return null;
        }

        if (element instanceof IType type) {
            return type;
        }

        if (element.getAncestor(IJavaElement.COMPILATION_UNIT) instanceof ICompilationUnit unit) {
            return unit.findPrimaryType();
        }

        if (element.getAncestor(IJavaElement.CLASS_FILE) instanceof IClassFile classFile
            && classFile instanceof IOrdinaryClassFile ordinary) {
            return ordinary.getType();
        }

        return null;
    }

    private static IFile accept(IFile file, Predicate<IFile> filter) {
        if (file != null && filter.test(file)) {
            return file;
        }

        return null;
    }
}
