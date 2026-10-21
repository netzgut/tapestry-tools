package net.netzgut.eclipse.tapestry.internal.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.hyperlink.AbstractHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.ui.statushandlers.StatusManager;

import net.netzgut.eclipse.tapestry.internal.core.ComponentIds;
import net.netzgut.eclipse.tapestry.internal.core.ComponentParameters;
import net.netzgut.eclipse.tapestry.internal.core.ComponentResolver;
import net.netzgut.eclipse.tapestry.internal.core.ParameterReference;
import net.netzgut.eclipse.tapestry.internal.core.PropertyExpression;
import net.netzgut.eclipse.tapestry.internal.core.PropertyPaths;
import net.netzgut.eclipse.tapestry.internal.core.RelatedFiles;
import net.netzgut.eclipse.tapestry.internal.core.TemplateLocator;
import net.netzgut.eclipse.tapestry.internal.core.TemplateReference;

/// Cmd/Ctrl+click in templates: component types and mixins open their class, `page="…"` the page,
/// `t:id` the fields and event handlers referring to the component, `message:key` the key's
/// definition, parameters (`<t:grid source="…">`, `<p:empty>`) their `@Parameter` field, and
/// property expressions (`${user.name}`, `value="user.name"`) the getter, field or method.
///
/// Registered for all text editors (whichever XML editor is used), so it first checks that the
/// document is a template. Detection runs on the UI thread while the mouse moves, so it only looks
/// at the text; the Java search happens when the link is clicked.
public class TemplateHyperlinkDetector extends AbstractHyperlinkDetector {

    @Override
    public IHyperlink[] detectHyperlinks(ITextViewer viewer, IRegion region, boolean canShowMultipleHyperlinks) {
        IDocument document = viewer.getDocument();
        IFile template = fileOf(document);
        if (template == null || !RelatedFiles.isTemplate(template)) {
            return null;
        }

        String content = document.get();
        int offset = region.getOffset();
        TemplateReference reference = TemplateReference.at(content, offset);
        if (reference == null) {
            ParameterReference parameter = ParameterReference.at(content, offset, false);
            if (parameter != null) {
                return new IHyperlink[] { new ParameterHyperlink(template, parameter) };
            }

            PropertyExpression expression = PropertyExpression.at(content, offset, false);
            if (expression == null) {
                return null;
            }

            return new IHyperlink[] { new PropertyHyperlink(template, expression) };
        }

        if (reference.kind() == TemplateReference.Kind.MESSAGE) {
            Region keyRegion = new Region(reference.offset(), reference.length());
            return new IHyperlink[] { new MessageKeyHyperlink(monitor -> TemplateLocator.findClasses(template, monitor),
                                                              reference.value(),
                                                              keyRegion) };
        }

        return new IHyperlink[] { new TemplateHyperlink(template, reference) };
    }

    /// The workspace file shown in an editor, via the file buffer shared by all editors on it.
    static IFile fileOf(IDocument document) {
        if (document == null) {
            return null;
        }

        ITextFileBuffer buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(document);
        if (buffer == null) {
            return null;
        }

        IPath location = buffer.getLocation();
        return FileBuffers.getWorkspaceFileAtLocation(location);
    }

    private record PropertyHyperlink(IFile template, PropertyExpression expression) implements IHyperlink {

        @Override
        public IRegion getHyperlinkRegion() {
            return new Region(this.expression.offset(), this.expression.length());
        }

        @Override
        public String getTypeLabel() {
            return "Tapestry";
        }

        @Override
        public String getHyperlinkText() {
            return "Open Property '" + this.expression.name() + "'";
        }

        @Override
        public void open() {
            try {
                List<IJavaElement> targets =
                    HandlerSupport.search("Resolving " + this.expression.name() + " failed", this::resolve);
                if (targets != null) {
                    String title = getHyperlinkText();
                    HandlerSupport.openOrChoose(title, "No property '" + this.expression.name() + "' found", targets);
                }
            }
            catch (ExecutionException e) {
                IStatus status = Status.error(e.getMessage(), e.getCause());
                StatusManager.getManager().handle(status, StatusManager.LOG | StatusManager.SHOW);
            }
        }

        private List<IJavaElement> resolve(IProgressMonitor monitor) throws CoreException {
            IType type = PropertyPaths.context(this.template, this.expression, monitor);
            if (type == null) {
                return List.of();
            }

            IMember member = PropertyPaths.find(type, this.expression.name(), monitor);
            if (member == null) {
                return List.of();
            }

            return List.of(member);
        }
    }

    private record ParameterHyperlink(IFile template, ParameterReference reference) implements IHyperlink {

        @Override
        public IRegion getHyperlinkRegion() {
            return new Region(this.reference.offset(), this.reference.length());
        }

        @Override
        public String getTypeLabel() {
            return "Tapestry";
        }

        @Override
        public String getHyperlinkText() {
            return "Open Parameter '" + this.reference.name() + "' of '" + this.reference.component() + "'";
        }

        @Override
        public void open() {
            try {
                List<IJavaElement> targets =
                    HandlerSupport.search("Resolving " + this.reference.name() + " failed", this::resolve);
                if (targets != null) {
                    String title = getHyperlinkText();
                    String notFoundMessage = "No parameter '"
                                             + this.reference.name()
                                             + "' found in '"
                                             + this.reference.component()
                                             + "'";
                    HandlerSupport.openOrChoose(title, notFoundMessage, targets);
                }
            }
            catch (ExecutionException e) {
                IStatus status = Status.error(e.getMessage(), e.getCause());
                StatusManager.getManager().handle(status, StatusManager.LOG | StatusManager.SHOW);
            }
        }

        private List<IJavaElement> resolve(IProgressMonitor monitor) throws CoreException {
            List<IJavaElement> targets = new ArrayList<>();
            IProject templateProject = this.template.getProject();
            IJavaProject project = JavaCore.create(templateProject);
            if (project.exists()) {
                ComponentResolver resolver = new ComponentResolver(project, monitor);
                List<IType> types = resolver.resolve(ComponentResolver.Kind.COMPONENT, this.reference.component());
                for (IType type : types) {
                    ComponentParameters.Parameter parameter =
                        ComponentParameters.find(type, this.reference.name(), monitor);
                    if (parameter != null) {
                        targets.add(parameter.field());
                    }
                }
            }

            return targets;
        }
    }

    private record TemplateHyperlink(IFile template, TemplateReference reference) implements IHyperlink {

        @Override
        public IRegion getHyperlinkRegion() {
            return new Region(this.reference.offset(), this.reference.length());
        }

        @Override
        public String getTypeLabel() {
            return "Tapestry";
        }

        @Override
        public String getHyperlinkText() {
            return switch (this.reference.kind()) {
                case COMPONENT -> "Open Component '" + this.reference.value() + "'";
                case MIXIN -> "Open Mixin '" + this.reference.value() + "'";
                case PAGE -> "Open Page '" + this.reference.value() + "'";
                case COMPONENT_ID -> "Open Field and Event Handlers of '" + this.reference.value() + "'";
                case MESSAGE -> "Open Message '" + this.reference.value() + "'";
            };
        }

        @Override
        public void open() {
            try {
                List<IJavaElement> targets =
                    HandlerSupport.search("Resolving " + this.reference.value() + " failed", this::resolve);
                if (targets != null) {
                    String title = getHyperlinkText();
                    String notFoundMessage = notFoundMessage();
                    HandlerSupport.openOrChoose(title, notFoundMessage, targets);
                }
            }
            catch (ExecutionException e) {
                IStatus status = Status.error(e.getMessage(), e.getCause());
                StatusManager.getManager().handle(status, StatusManager.LOG | StatusManager.SHOW);
            }
        }

        private List<IJavaElement> resolve(IProgressMonitor monitor) throws CoreException {
            List<IJavaElement> targets = new ArrayList<>();
            if (this.reference.kind() == TemplateReference.Kind.COMPONENT_ID) {
                for (IType type : TemplateLocator.findClasses(this.template, monitor)) {
                    List<IMember> references = ComponentIds.references(type, this.reference.value());
                    targets.addAll(references);
                }
                return targets;
            }

            IProject templateProject = this.template.getProject();
            IJavaProject project = JavaCore.create(templateProject);
            if (!project.exists()) {
                return targets;
            }

            ComponentResolver.Kind kind = switch (this.reference.kind()) {
                case MIXIN -> ComponentResolver.Kind.MIXIN;
                case PAGE -> ComponentResolver.Kind.PAGE;
                default -> ComponentResolver.Kind.COMPONENT;
            };

            ComponentResolver resolver = new ComponentResolver(project, monitor);
            List<IType> types = resolver.resolve(kind, this.reference.value());
            targets.addAll(types);
            return targets;
        }

        private String notFoundMessage() {
            return switch (this.reference.kind()) {
                case COMPONENT_ID -> "No field or event handler for '" + this.reference.value() + "' found";
                default -> "No class found for '" + this.reference.value() + "'";
            };
        }
    }
}
