package net.netzgut.eclipse.tapestry.internal.ui;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ICompletionProposal;

import net.netzgut.eclipse.tapestry.internal.core.RelatedFiles;
import net.netzgut.eclipse.tapestry.internal.core.TapestryPreferences;
import net.netzgut.eclipse.tapestry.internal.core.TemplateCompletion;

/// Template content assist for any editor: [TemplateContentAssistProcessor] (Generic Editor) and
/// [WtpTemplateProposalComputer] (WTP XML editor) only adapt this to their editor's API.
final class TemplateProposals {

    private TemplateProposals() {
    }

    static List<ICompletionProposal> compute(IDocument document, int offset, IProgressMonitor monitor) {
        if (!TapestryPreferences.isEnabled(TapestryPreferences.PROPOSE_TEMPLATE_NAMES)) {
            return List.of();
        }

        IFile template = TemplateHyperlinkDetector.fileOf(document);
        if (template == null || !RelatedFiles.isTemplate(template)) {
            return List.of();
        }

        try {
            String content = document.get();
            List<TemplateCompletion.Candidate> candidates =
                TemplateCompletion.compute(template, content, offset, monitor);
            return candidates.stream()
                             .<ICompletionProposal> map(candidate -> new TemplateProposal(candidate, offset))
                             .toList();
        }
        catch (CoreException e) {
            ILog.of(TemplateProposals.class).error("Computing template proposals failed", e);
            return List.of();
        }
    }
}
