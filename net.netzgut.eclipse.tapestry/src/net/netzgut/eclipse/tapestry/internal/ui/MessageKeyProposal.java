package net.netzgut.eclipse.tapestry.internal.ui;

import java.util.Locale;
import java.util.regex.Pattern;

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

import net.netzgut.eclipse.tapestry.internal.core.MessageKey;
import net.netzgut.eclipse.tapestry.internal.core.Strings;

/// Inserts a message key into the string literal, replacing what was typed of it.
class MessageKeyProposal
    implements
        IJavaCompletionProposal,
        ICompletionProposalExtension2,
        ICompletionProposalExtension3,
        ICompletionProposalExtension6 {

    private static final int     RELEVANCE        = 2_000;
    private static final int     MAX_VALUE_LENGTH = 60;
    private static final Pattern WHITESPACE       = Pattern.compile("\\s+");

    private final MessageKey key;
    private final int        replacementOffset;
    private final int        initialPrefixLength;
    private Point            selection;

    MessageKeyProposal(MessageKey key, int replacementOffset, int initialPrefixLength) {
        this.key = key;
        this.replacementOffset = replacementOffset;
        this.initialPrefixLength = initialPrefixLength;
    }

    /// Keys are dotted/dashed (`error.required-field`): prefix or substring, case-insensitive.
    static boolean matches(String prefix, String key) {
        String lowerCaseKey = key.toLowerCase(Locale.ROOT);
        String lowerCasePrefix = prefix.toLowerCase(Locale.ROOT);
        return lowerCaseKey.contains(lowerCasePrefix);
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
            document.replace(this.replacementOffset, offset - this.replacementOffset, this.key.key());
            this.selection = new Point(this.replacementOffset + this.key.key().length(), 0);
        }
        catch (BadLocationException _) {
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
        String summary = summary(this.key.value());
        return new StyledString(this.key.key()).append(" - " + summary, StyledString.QUALIFIER_STYLER);
    }

    /// A message value on a single, shortened line.
    static String summary(String value) {
        String stripped = value.strip();
        String line = WHITESPACE.matcher(stripped).replaceAll(" ");
        if (line.length() > MAX_VALUE_LENGTH) {
            return line.substring(0, MAX_VALUE_LENGTH) + "…";
        }

        return line;
    }

    @Override
    public String getAdditionalProposalInfo() {
        return details(this.key);
    }

    /// The value in every catalog, so translations can be compared.
    static String details(MessageKey key) {
        StringBuilder info = new StringBuilder("<dl>");
        for (MessageKey.Definition definition : key.definitions()) {
            info.append("<dt><code>").append(Strings.escapeHtml(definition.file().getName())).append("</code></dt><dd>")
                .append(Strings.escapeHtml(definition.value())).append("</dd>");
        }
        return info.append("</dl>").toString();
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
        return RELEVANCE;
    }

    @Override
    public boolean validate(IDocument document, int offset, DocumentEvent event) {
        if (offset < this.replacementOffset) {
            return false;
        }

        try {
            String typed = document.get(this.replacementOffset, offset - this.replacementOffset);
            return matches(typed, this.key.key());
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
        return this.key.key();
    }

    @Override
    public int getPrefixCompletionStart(IDocument document, int completionOffset) {
        return this.replacementOffset;
    }
}
