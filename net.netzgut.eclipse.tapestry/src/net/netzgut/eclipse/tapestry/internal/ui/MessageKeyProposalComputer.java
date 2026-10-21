package net.netzgut.eclipse.tapestry.internal.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.ui.text.java.ContentAssistInvocationContext;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposalComputer;
import org.eclipse.jdt.ui.text.java.JavaContentAssistInvocationContext;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContextInformation;

import net.netzgut.eclipse.tapestry.internal.core.MessageCalls;
import net.netzgut.eclipse.tapestry.internal.core.MessageCatalogs;
import net.netzgut.eclipse.tapestry.internal.core.MessageKey;
import net.netzgut.eclipse.tapestry.internal.core.TapestryPreferences;

/// Proposes message keys from the class's catalogs inside `messages.get("|")`, `format` and
/// `contains`. Registered for the string partition only.
public class MessageKeyProposalComputer implements IJavaCompletionProposalComputer {

    /// Cheap pre-check before parsing: `.get("ab|`, `.format( "|`
    private static final Pattern CALL_BEFORE_CURSOR =
        Pattern.compile(".*\\.\\s*(?:get|format|contains)\\s*\\(\\s*\"[^\"]*");

    @Override
    public List<ICompletionProposal> computeCompletionProposals(ContentAssistInvocationContext context,
                                                                IProgressMonitor monitor) {
        if (!(context instanceof JavaContentAssistInvocationContext javaContext)
            || javaContext.getCompilationUnit() == null
            || !TapestryPreferences.isEnabled(TapestryPreferences.PROPOSE_MESSAGE_KEYS)) {
            return List.of();
        }

        try {
            return compute(javaContext, monitor);
        }
        catch (CoreException | BadLocationException e) {
            ILog.of(getClass()).error("Computing Tapestry message key proposals failed", e);
            return List.of();
        }
    }

    private List<ICompletionProposal> compute(JavaContentAssistInvocationContext context,
                                              IProgressMonitor monitor) throws CoreException, BadLocationException {
        IDocument document = context.getDocument();
        int offset = context.getInvocationOffset();
        IRegion line = document.getLineInformationOfOffset(offset);
        String beforeCursor = document.get(line.getOffset(), offset - line.getOffset());
        if (!CALL_BEFORE_CURSOR.matcher(beforeCursor).matches()) {
            return List.of();
        }

        ICompilationUnit unit = context.getCompilationUnit();
        MessageCalls.KeyArgument argument = MessageCalls.keyArgumentAt(unit, offset, monitor);
        if (argument == null
            || !(unit.getElementAt(offset) instanceof IJavaElement element)
            || !(element.getAncestor(IJavaElement.TYPE) instanceof IType type)) {
            return List.of();
        }

        List<ICompletionProposal> proposals = new ArrayList<>();
        for (MessageKey key : MessageCatalogs.keys(type)) {
            if (MessageKeyProposal.matches(argument.prefix(), key.key())) {
                proposals.add(new MessageKeyProposal(key, argument.start(), argument.prefix().length()));
            }
        }

        return proposals;
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
