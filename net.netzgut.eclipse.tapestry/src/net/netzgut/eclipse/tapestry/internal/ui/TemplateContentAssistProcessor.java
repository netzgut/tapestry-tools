package net.netzgut.eclipse.tapestry.internal.ui;

import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.contentassist.IContextInformationValidator;

/// Template content assist in the Generic Editor, which runs processors in a background thread.
/// Registered for all XML files; [TemplateProposals] ignores everything but templates.
public class TemplateContentAssistProcessor implements IContentAssistProcessor {

    @Override
    public ICompletionProposal[] computeCompletionProposals(ITextViewer viewer, int offset) {
        IDocument document = viewer.getDocument();
        List<ICompletionProposal> proposals = TemplateProposals.compute(document, offset, new NullProgressMonitor());
        return proposals.toArray(ICompletionProposal[]::new);
    }

    @Override
    public IContextInformation[] computeContextInformation(ITextViewer viewer, int offset) {
        return null;
    }

    /// `<t:`, `message:` and `user.` open the proposals by themselves.
    @Override
    public char[] getCompletionProposalAutoActivationCharacters() {
        return new char[] { ':', '.' };
    }

    @Override
    public char[] getContextInformationAutoActivationCharacters() {
        return null;
    }

    @Override
    public String getErrorMessage() {
        return null;
    }

    @Override
    public IContextInformationValidator getContextInformationValidator() {
        return null;
    }
}
