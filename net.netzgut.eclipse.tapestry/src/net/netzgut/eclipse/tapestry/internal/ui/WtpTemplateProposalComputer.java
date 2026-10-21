package net.netzgut.eclipse.tapestry.internal.ui;

import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.wst.sse.ui.contentassist.CompletionProposalInvocationContext;
import org.eclipse.wst.sse.ui.contentassist.ICompletionProposalComputer;

/// Template content assist in the WTP XML editor. WTP is an optional dependency: this class is only
/// loaded by WTP's own extension point, so without WTP it is never touched.
public class WtpTemplateProposalComputer implements ICompletionProposalComputer {

    @Override
    public List<?> computeCompletionProposals(CompletionProposalInvocationContext context, IProgressMonitor monitor) {
        return TemplateProposals.compute(context.getDocument(), context.getInvocationOffset(), monitor);
    }

    @Override
    public List<?> computeContextInformation(CompletionProposalInvocationContext context, IProgressMonitor monitor) {
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
