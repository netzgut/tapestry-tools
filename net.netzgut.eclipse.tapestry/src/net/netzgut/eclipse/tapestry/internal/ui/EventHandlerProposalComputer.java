package net.netzgut.eclipse.tapestry.internal.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jdt.ui.text.java.ContentAssistInvocationContext;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposalComputer;
import org.eclipse.jdt.ui.text.java.JavaContentAssistInvocationContext;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContextInformation;

import net.netzgut.eclipse.tapestry.internal.core.EventHandler;
import net.netzgut.eclipse.tapestry.internal.core.EventHandlers;
import net.netzgut.eclipse.tapestry.internal.core.HandlerMethod;
import net.netzgut.eclipse.tapestry.internal.core.LifecycleMethod;
import net.netzgut.eclipse.tapestry.internal.core.TapestryPreferences;

/// Proposes event handler methods (`onSuccessFromLoginForm()`) for the components in the class's
/// template, page events (`onActivate()`) and render phase/lifecycle methods (`setupRender()`). JDT
/// calls every registered computer on Ctrl+Space; we only contribute where a method declaration
/// can start.
public class EventHandlerProposalComputer implements IJavaCompletionProposalComputer {

    private static final String MODIFIERS = "(?:(?:public|protected|private|static|final|synchronized)\\s+)*";

    /// Only whitespace/modifiers before the cursor: propose complete declarations including
    /// return type.
    private static final Pattern MEMBER_START = Pattern.compile("\\s*" + MODIFIERS);

    /// A return type already typed (`void on|`): propose only name, parameters and body.
    private static final Pattern AFTER_RETURN_TYPE = Pattern.compile("\\s*" + MODIFIERS + "[\\w.]+(?:<[^>]*>)?\\s+");

    @Override
    public List<ICompletionProposal> computeCompletionProposals(ContentAssistInvocationContext context,
                                                                IProgressMonitor monitor) {
        if (!(context instanceof JavaContentAssistInvocationContext javaContext)
            || javaContext.getCompilationUnit() == null) {
            return List.of();
        }

        try {
            return compute(javaContext, monitor);
        }
        catch (CoreException | BadLocationException e) {
            ILog.of(getClass()).error("Computing Tapestry event handler proposals failed", e);
            return List.of();
        }
    }

    private List<ICompletionProposal> compute(JavaContentAssistInvocationContext context,
                                              IProgressMonitor monitor) throws CoreException, BadLocationException {
        IDocument document = context.getDocument();
        String prefix = context.computeIdentifierPrefix().toString();
        int start = context.getInvocationOffset() - prefix.length();

        IRegion line = document.getLineInformationOfOffset(start);
        String beforePrefix = document.get(line.getOffset(), start - line.getOffset());
        boolean withReturnType;
        if (MEMBER_START.matcher(beforePrefix).matches()) {
            withReturnType = true;
        }
        else if (AFTER_RETURN_TYPE.matcher(beforePrefix).matches()) {
            withReturnType = false;
        }
        else {
            return List.of();
        }

        ICompilationUnit compilationUnit = context.getCompilationUnit();
        IType type = enclosingTopLevelType(compilationUnit, start, prefix);
        if (type == null) {
            return List.of();
        }

        String indent = beforePrefix.substring(0, beforePrefix.length() - beforePrefix.stripLeading().length());
        String unit = indentationUnit(compilationUnit);
        String lineDelimiter = TextUtilities.getDefaultLineDelimiter(document);

        boolean annotate = TapestryPreferences.annotateHandlers();
        List<HandlerMethod> candidates = new ArrayList<>();
        if (TapestryPreferences.isEnabled(TapestryPreferences.PROPOSE_EVENT_HANDLERS)) {
            List<EventHandler> missingHandlers = EventHandlers.missingHandlers(type, monitor);
            candidates.addAll(missingHandlers);
        }

        if (TapestryPreferences.isEnabled(TapestryPreferences.PROPOSE_LIFECYCLE_METHODS)
            && EventHandlers.isComponentClass(type)) {
            List<LifecycleMethod> missingLifecycleMethods = LifecycleMethod.missing(type);
            candidates.addAll(missingLifecycleMethods);
        }

        List<ICompletionProposal> proposals = new ArrayList<>();
        for (HandlerMethod handler : candidates) {
            if (EventHandlerProposal.matches(prefix, handler.methodName())) {
                proposals.add(new EventHandlerProposal(handler,
                                                       annotate,
                                                       compilationUnit,
                                                       line.getOffset() + indent.length(),
                                                       start,
                                                       prefix.length(),
                                                       withReturnType,
                                                       indent,
                                                       unit,
                                                       lineDelimiter));
            }
        }

        return proposals;
    }

    /// The class whose body we are in, or `null` inside a method/field initializer. A half-typed
    /// declaration (`void onSu`) may already show up as a member named like the prefix, which
    /// is fine.
    private static IType enclosingTopLevelType(ICompilationUnit unit, int offset, String prefix) throws CoreException {
        IJavaElement element = unit.getElementAt(offset);
        IType type;
        if (element instanceof IType candidate) {
            type = candidate;
        }
        else if (element instanceof IMember member && !prefix.isEmpty() && member.getElementName().equals(prefix)) {
            type = member.getDeclaringType();
        }
        else {
            return null;
        }

        if (type != null && type.getDeclaringType() == null) {
            return type;
        }

        return null;
    }

    private static String indentationUnit(ICompilationUnit unit) {
        String tabChar = unit.getJavaProject().getOption(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, true);
        if (JavaCore.TAB.equals(tabChar)) {
            return "\t";
        }

        String size = unit.getJavaProject().getOption(DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE, true);
        try {
            return " ".repeat(Integer.parseInt(size));
        }
        catch (NumberFormatException e) {
            return "    ";
        }
    }

    @Override
    public List<IContextInformation> computeContextInformation(ContentAssistInvocationContext context,
                                                               IProgressMonitor monitor) {
        return List.of();
    }

    @Override
    public String getErrorMessage() {
        return null;
    }

    @Override
    public void sessionStarted() {
        // NOOP
    }

    @Override
    public void sessionEnded() {
        // NOOP
    }
}
