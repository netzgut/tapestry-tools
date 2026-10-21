package net.netzgut.eclipse.tapestry.internal.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.hyperlink.AbstractHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.statushandlers.StatusManager;
import org.eclipse.ui.texteditor.ITextEditor;

import net.netzgut.eclipse.tapestry.internal.core.ComponentIds;
import net.netzgut.eclipse.tapestry.internal.core.SwitchTargets;
import net.netzgut.eclipse.tapestry.internal.core.TemplateLocator;
import net.netzgut.eclipse.tapestry.internal.core.TemplateReference;

/// Cmd/Ctrl+click in the Java editor on the name of a `@Component`/`@InjectComponent` field or an
/// event handler (`onSuccessFromLoginForm`, `@OnEvent(component = "loginForm")`) opens the
/// component's element in the template.
public class JavaTemplateHyperlinkDetector extends AbstractHyperlinkDetector {

    @Override
    public IHyperlink[] detectHyperlinks(ITextViewer viewer, IRegion region, boolean canShowMultipleHyperlinks) {
        ITextEditor editor = getAdapter(ITextEditor.class);
        if (editor == null) {
            return null;
        }

        IEditorInput input = editor.getEditorInput();
        if (!(JavaUI.getEditorInputTypeRoot(input) instanceof ICompilationUnit unit)) {
            return null;
        }

        try {
            int offset = region.getOffset();
            IJavaElement element = unit.getElementAt(offset);
            String id = switch (element) {
                case IField field -> ComponentIds.ofField(field);
                case IMethod method -> ComponentIds.ofMethod(method);
                case null, default -> null;
            };

            if (id == null) {
                return null;
            }

            IMember member = (IMember) element;
            ISourceRange name = member.getNameRange();
            if (name == null || offset < name.getOffset() || offset > name.getOffset() + name.getLength()) {
                return null;
            }

            IType declaringType = member.getDeclaringType();
            IType topLevelType = topLevel(declaringType);
            Region nameRegion = new Region(name.getOffset(), name.getLength());
            return new IHyperlink[] { new ComponentIdHyperlink(topLevelType, id, nameRegion) };
        }
        catch (CoreException _) {
            return null; // e.g. the editor's unit is not on the build path
        }
    }

    private static IType topLevel(IType type) {
        IType current = type;
        while (current.getDeclaringType() != null) {
            current = current.getDeclaringType();
        }
        return current;
    }

    private record ComponentIdHyperlink(IType type, String id, IRegion region) implements IHyperlink {

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
            return "Open '" + this.id + "' in Template";
        }

        @Override
        public void open() {
            try {
                List<FileLocation> targets = HandlerSupport.search("Searching templates failed", this::locate);
                if (targets != null) {
                    String title = getHyperlinkText();
                    String notFoundMessage = "No component '"
                                             + this.id
                                             + "' in the templates of "
                                             + this.type.getElementName();
                    HandlerSupport.openOrChoose(title, notFoundMessage, targets);
                }
            }
            catch (ExecutionException e) {
                IStatus status = Status.error(e.getMessage(), e.getCause());
                StatusManager.getManager().handle(status, StatusManager.LOG | StatusManager.SHOW);
            }
        }

        /// All elements with this id; only those of the main template if it has the id (`Index.tml`
        /// over `Index_de.tml`).
        private List<FileLocation> locate(IProgressMonitor monitor) throws CoreException {
            List<IFile> templates = TemplateLocator.findTemplates(this.type);
            IFile preferred = SwitchTargets.preferredTemplate(templates,
                                                              this.type.getElementName(),
                                                              this.type.getFullyQualifiedName());
            List<FileLocation> all = new ArrayList<>();
            List<FileLocation> inPreferred = new ArrayList<>();
            for (IFile template : templates) {
                String content = TemplateLocator.read(template);
                for (TemplateReference reference : TemplateReference.componentIds(content)) {
                    if (reference.value().equalsIgnoreCase(this.id)) {
                        int line = FileLocation.lineOf(content, reference.offset());
                        FileLocation location =
                            new FileLocation(template, reference.offset(), reference.length(), line);
                        all.add(location);
                        if (template.equals(preferred)) {
                            inPreferred.add(location);
                        }
                    }
                }
            }

            if (inPreferred.isEmpty()) {
                return all;
            }

            return inPreferred;
        }
    }
}
