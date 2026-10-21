package net.netzgut.eclipse.tapestry.internal.ui;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;

import net.netzgut.eclipse.tapestry.internal.core.MessageKey;
import net.netzgut.eclipse.tapestry.internal.core.TemplateCompletion;

/// A component, mixin or page name or message key in a template. Replaces the whole name, including
/// what follows the cursor, so completing in the middle of `t:type="lay|out"` doesn't leave a
/// tail behind.
class TemplateProposal
    implements
        ICompletionProposal,
        ICompletionProposalExtension2,
        ICompletionProposalExtension3,
        ICompletionProposalExtension6 {

    private final TemplateCompletion.Candidate candidate;
    private final int                          invocationOffset;
    private Point                              selection;

    TemplateProposal(TemplateCompletion.Candidate candidate, int invocationOffset) {
        this.candidate = candidate;
        this.invocationOffset = invocationOffset;
    }

    @Override
    public void apply(IDocument document) {
        apply(document, this.invocationOffset);
    }

    @Override
    public void apply(ITextViewer viewer, char trigger, int stateMask, int offset) {
        IDocument document = viewer.getDocument();
        apply(document, offset);
    }

    private void apply(IDocument document, int offset) {
        // Characters typed since the invocation extend the original name
        int length =
            Math.max(this.candidate.length() + offset - this.invocationOffset, offset - this.candidate.offset());
        try {
            document.replace(this.candidate.offset(), length, this.candidate.replacement());
            this.selection = new Point(this.candidate.offset() + this.candidate.cursor(), 0);
        }
        catch (BadLocationException e) {
            // the document changed underneath us; nothing sensible to do
        }
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
        String qualifier = this.candidate.qualifier();
        if (this.candidate.element() instanceof MessageKey) {
            qualifier = MessageKeyProposal.summary(qualifier);
        }

        return new StyledString(this.candidate.label()).append(" - " + qualifier, StyledString.QUALIFIER_STYLER);
    }

    @Override
    public String getAdditionalProposalInfo() {
        if (this.candidate.element() instanceof MessageKey key) {
            return MessageKeyProposal.details(key);
        }

        return null;
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
    public boolean validate(IDocument document, int offset, DocumentEvent event) {
        if (offset < this.candidate.offset()) {
            return false;
        }

        try {
            String typed = document.get(this.candidate.offset(), offset - this.candidate.offset());
            return TemplateCompletion.accepts(this.candidate, typed);
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
        return this.candidate.replacement();
    }

    @Override
    public int getPrefixCompletionStart(IDocument document, int completionOffset) {
        return this.candidate.offset();
    }
}
