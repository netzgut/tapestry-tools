package net.netzgut.eclipse.tapestry.internal.ui;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.text.edits.MalformedTreeException;

import net.netzgut.eclipse.tapestry.internal.core.EventHandler;
import net.netzgut.eclipse.tapestry.internal.core.EventHandlers;
import net.netzgut.eclipse.tapestry.internal.core.HandlerMethod;
import net.netzgut.eclipse.tapestry.internal.core.LifecycleMethod;
import net.netzgut.eclipse.tapestry.internal.core.NameMatch;

/// Inserts an event handler (`@OnEvent`) or lifecycle method (`@SetupRender`…), optionally
/// annotated, adds the imports and places the cursor in its body. The extension interfaces add what
/// the plain `ICompletionProposal` lacks: filtering while typing (2), prefix handling (3) and styled
/// labels (6).
class EventHandlerProposal
    implements
        IJavaCompletionProposal,
        ICompletionProposalExtension2,
        ICompletionProposalExtension3,
        ICompletionProposalExtension6 {

    // Above JDT's own proposals: when these apply, they are almost certainly what is wanted.
    // Events from the template first, they are more specific than the generic lifecycle methods.
    private static final int EVENT_RELEVANCE     = 2_000;
    private static final int LIFECYCLE_RELEVANCE = 1_900;

    private static final String ON_EVENT = "org.apache.tapestry5.annotations.OnEvent";

    private final HandlerMethod    handler;
    private final boolean          annotate;
    private final ICompilationUnit unit;
    private final int              declarationOffset;
    private final int              replacementOffset;
    private final int              initialPrefixLength;
    private final boolean          withReturnType;
    private final String           indent;
    private final String           indentationUnit;
    private final String           lineDelimiter;
    private Point                  selection;

    /// @param annotate whether to add `@OnEvent`, `@SetupRender`…
    /// @param unit for adding imports; `null` to use simple names without imports
    /// @param declarationOffset where the declaration starts (before modifiers), the annotation goes
    ///            there
    /// @param replacementOffset start of the typed method name prefix
    EventHandlerProposal(HandlerMethod handler,
                         boolean annotate,
                         ICompilationUnit unit,
                         int declarationOffset,
                         int replacementOffset,
                         int initialPrefixLength,
                         boolean withReturnType,
                         String indent,
                         String indentationUnit,
                         String lineDelimiter) {
        this.handler = handler;
        this.annotate = annotate;
        this.unit = unit;
        this.declarationOffset = declarationOffset;
        this.replacementOffset = replacementOffset;
        this.initialPrefixLength = initialPrefixLength;
        this.withReturnType = withReturnType;
        this.indent = indent;
        this.indentationUnit = indentationUnit;
        this.lineDelimiter = lineDelimiter;
    }

    /// Prefix, camel case (`oSFL`) or substring (`login`) match.
    static boolean matches(String prefix, String methodName) {
        return NameMatch.matches(prefix, methodName);
    }

    @Override
    public void apply(IDocument document) {
        apply(document, this.replacementOffset + this.initialPrefixLength);
    }

    @Override
    public void apply(ITextViewer viewer, char trigger, int stateMask, int offset) {
        IDocument document = viewer.getDocument();
        apply(document, offset);
    }

    private void apply(IDocument document, int offset) {
        try {
            // Imports first: the rewrite computes its edits from the unit's source, which must still match the
            // document. Everything we insert afterwards lies below the imports and shifts by the same amount.
            ImportRewrite imports = null;
            if (this.unit != null && this.annotate) {
                imports = ImportRewrite.create(this.unit, true);
            }

            String annotation = null;
            if (this.annotate) {
                annotation = annotation(imports);
            }

            int shift = 0;
            if (imports != null && imports.hasRecordedChanges()) {
                int length = document.getLength();
                imports.rewriteImports(null).apply(document);
                shift = document.getLength() - length;
            }

            String body = this.indent + this.indentationUnit;
            StringBuilder text = new StringBuilder();
            if (this.withReturnType) {
                text.append(this.handler.returnType()).append(' ');
            }
            text.append(this.handler.methodName()).append("() {").append(this.lineDelimiter).append(body);

            int cursor = text.length();
            if (!this.handler.returnsVoid() && this.withReturnType) {
                text.append(this.lineDelimiter).append(body).append("return null;");
            }
            text.append(this.lineDelimiter).append(this.indent).append('}');

            // Bottom to top, so the annotation insert doesn't move the method's offsets
            String declaration = text.toString();
            document.replace(this.replacementOffset + shift, offset - this.replacementOffset, declaration);
            int annotationLength = 0;
            if (annotation != null) {
                String annotationLine = annotation + this.lineDelimiter + this.indent;
                document.replace(this.declarationOffset + shift, 0, annotationLine);
                annotationLength = annotationLine.length();
            }

            this.selection = new Point(this.replacementOffset + shift + annotationLength + cursor, 0);
        }
        catch (BadLocationException | MalformedTreeException _) {
            // the document changed underneath us; nothing sensible to do
        }
        catch (CoreException e) {
            ILog.of(getClass()).error("Adding imports for " + this.handler.methodName() + " failed", e);
        }
    }

    /// `@OnEvent(value = EventConstants.SUCCESS, component = "loginForm")` or `@SetupRender`, the
    /// style used in our projects.
    private String annotation(ImportRewrite imports) {
        return switch (this.handler) {
            case EventHandler event -> onEvent(event, imports);
            case LifecycleMethod lifecycle -> "@" + typeName(imports, lifecycle.qualifiedAnnotation());
        };
    }

    private static String onEvent(EventHandler handler, ImportRewrite imports) {
        StringBuilder annotation = new StringBuilder("@").append(typeName(imports, ON_EVENT)).append("(value = ");
        if (handler.eventConstant() != null) {
            annotation.append(typeName(imports, EventHandlers.EVENT_CONSTANTS)).append('.')
                      .append(handler.eventConstant());
        }
        else {
            annotation.append('"').append(handler.event()).append('"');
        }

        if (handler.componentId() != null) {
            annotation.append(", component = \"").append(handler.componentId()).append('"');
        }

        return annotation.append(')').toString();
    }

    /// The name to use in code: simple if importable, qualified if it would clash with
    /// another import.
    private static String typeName(ImportRewrite imports, String qualifiedName) {
        if (imports == null) {
            return Signature.getSimpleName(qualifiedName);
        }

        return imports.addImport(qualifiedName);
    }

    @Override
    public Point getSelection(IDocument document) {
        return this.selection;
    }

    @Override
    public String getDisplayString() {
        return getStyledDisplayString().getString();
    }

    @Override
    public StyledString getStyledDisplayString() {
        StyledString label = new StyledString(this.handler.methodName() + "()");
        String qualifier = switch (this.handler) {
            case EventHandler event -> describeSource(event);
            case LifecycleMethod lifecycle -> {
                if (lifecycle.description().startsWith("Render")) {
                    yield "render phase";
                }

                yield "page lifecycle";
            }
        };
        label.append(" - " + qualifier, StyledString.QUALIFIER_STYLER);

        return label;
    }

    @Override
    public String getAdditionalProposalInfo() {
        return switch (this.handler) {
            case EventHandler event when event.templateName() == null -> "Handles the <b>"
                                                                         + event.event()
                                                                         + "</b> event of the page.";

            case EventHandler event -> "Handles the <b>"
                                       + event.event()
                                       + "</b> event of "
                                       + describeSource(event)
                                       + ", declared in <code>"
                                       + event.templateName()
                                       + "</code>.";

            case LifecycleMethod lifecycle -> lifecycle.description() + ".";
        };
    }

    private static String describeSource(EventHandler handler) {
        if (handler.templateName() == null) {
            return "page";
        }

        String type = "";
        if (handler.componentType() != null) {
            type = " (" + handler.componentType() + ")";
        }

        if (handler.componentId() == null) {
            return "any component" + type;
        }

        return "component '" + handler.componentId() + "'" + type;
    }

    @Override
    public Image getImage() {
        return TapestryImages.logo();
    }

    @Override
    public IContextInformation getContextInformation() {
        return null;
    }

    @Override
    public int getRelevance() {
        if (this.handler instanceof LifecycleMethod) {
            return LIFECYCLE_RELEVANCE;
        }

        return EVENT_RELEVANCE;
    }

    @Override
    public boolean validate(IDocument document, int offset, DocumentEvent event) {
        if (offset < this.replacementOffset) {
            return false;
        }

        try {
            String typed = document.get(this.replacementOffset, offset - this.replacementOffset);
            return matches(typed, this.handler.methodName());
        }
        catch (BadLocationException e) {
            return false;
        }
    }

    @Override
    public void selected(ITextViewer viewer, boolean smartToggle) {
        // NOOP
    }

    @Override
    public void unselected(ITextViewer viewer) {
        // NOOP
    }

    @Override
    public IInformationControlCreator getInformationControlCreator() {
        return null;
    }

    @Override
    public CharSequence getPrefixCompletionText(IDocument document, int completionOffset) {
        return this.handler.methodName();
    }

    @Override
    public int getPrefixCompletionStart(IDocument document, int completionOffset) {
        return this.replacementOffset;
    }
}
