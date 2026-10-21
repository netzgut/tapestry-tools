package net.netzgut.eclipse.tapestry.internal.ui;

import java.io.IOException;
import java.io.Reader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.ui.text.java.hover.JavadocBrowserInformationControlInput;
import org.eclipse.jdt.internal.ui.text.java.hover.JavadocHover;
import org.eclipse.jdt.ui.JavadocContentAccess;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextHoverExtension;
import org.eclipse.jface.text.ITextHoverExtension2;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.information.IInformationProviderExtension2;

import net.netzgut.eclipse.tapestry.internal.core.ComponentParameters;
import net.netzgut.eclipse.tapestry.internal.core.RelatedFiles;
import net.netzgut.eclipse.tapestry.internal.core.Strings;
import net.netzgut.eclipse.tapestry.internal.core.TapestryPreferences;
import net.netzgut.eclipse.tapestry.internal.core.TemplateElement;

/// Hover in templates: the Javadoc of components, mixins and pages with a summary of their
/// parameters, of parameters (their `@Parameter` field) and of properties (getter, field or method).
///
/// The Javadoc and its presentation come from JDT's Java editor hover (internal API), so it looks
/// and behaves like hovering the class in Java: links, F2 to focus, Javadoc view. The Tapestry part
/// is appended to its HTML.
///
/// Registered for the WTP XML editor and the Generic Editor, for all XML files.
@SuppressWarnings("restriction")
public class TemplateTextHover
    implements
        ITextHover,
        ITextHoverExtension,
        ITextHoverExtension2,
        IInformationProviderExtension2 {

    private static final Pattern TAG                = Pattern.compile("<[^>]*>");
    private static final Pattern FIRST_SENTENCE     = Pattern.compile("^(.*?[.!?])(?:\\s|$)");
    private static final Pattern WHITESPACE         = Pattern.compile("\\s+");
    private static final int     MAX_SUMMARY_LENGTH = 120;

    private final JavadocHover javadoc = new JavadocHover();

    @Override
    public IRegion getHoverRegion(ITextViewer viewer, int offset) {
        IDocument document = viewer.getDocument();
        if (!isEnabled() || !isTemplate(document)) {
            return null;
        }

        String text = document.get();
        return TemplateElement.regionAt(text, offset);
    }

    @Override
    public Object getHoverInfo2(ITextViewer viewer, IRegion region) {
        IDocument document = viewer.getDocument();
        if (!isEnabled() || !isTemplate(document)) {
            return null;
        }

        try {
            IFile file = TemplateHyperlinkDetector.fileOf(document);
            String text = document.get();
            TemplateElement element = TemplateElement.at(file,
                                                         text,
                                                         region.getOffset(),
                                                         new NullProgressMonitor());
            if (element != null) {
                return input(element);
            }
        }
        catch (CoreException e) {
            ILog.of(TemplateTextHover.class).error("Computing the template hover failed", e);
        }
        return null;
    }

    /// Only for callers without [ITextHoverExtension2]; the HTML is shown as text.
    @Override
    @Deprecated
    public String getHoverInfo(ITextViewer viewer, IRegion region) {
        Object info = getHoverInfo2(viewer, region);
        if (info instanceof JavadocBrowserInformationControlInput input) {
            return input.getHtml();
        }

        return null;
    }

    @Override
    public IInformationControlCreator getHoverControlCreator() {
        return this.javadoc.getHoverControlCreator();
    }

    @Override
    public IInformationControlCreator getInformationPresenterControlCreator() {
        return this.javadoc.getInformationPresenterControlCreator();
    }

    private static JavadocBrowserInformationControlInput input(TemplateElement element) throws CoreException {
        IMember member = element.member();
        ITypeRoot typeRoot = member.getTypeRoot();
        IRegion nameRegion = nameRegion(member);
        JavadocBrowserInformationControlInput input =
            JavadocHover.getHoverInfo(new IJavaElement[] { member }, typeRoot, nameRegion, null);
        if (input == null) {
            return null;
        }

        String section = section(element);
        if (section.isEmpty()) {
            return input;
        }

        String html = input.getHtml();
        int end = html.lastIndexOf("</body>");
        if (end < 0) {
            html = html + section;
        }
        else {
            html = html.substring(0, end) + section + html.substring(end);
        }
        return new JavadocBrowserInformationControlInput(null, member, html, input.getLeadingImageWidth());
    }

    /// The Tapestry part: what the element is in the template, and a component's parameters.
    private static String section(TemplateElement element) throws JavaModelException {
        StringBuilder html = new StringBuilder();
        switch (element) {
            case TemplateElement.Component component -> {
                String kind = switch (component.kind()) {
                    case MIXIN -> "Mixin";
                    case PAGE -> "Page";
                    default -> "Component";
                };
                html.append("<dl><dt>Tapestry ").append(kind).append(":</dt><dd><code>")
                    .append(Strings.escapeHtml(component.name())).append("</code></dd>");

                if (!component.parameters().isEmpty()) {
                    html.append("<dt>Parameters:</dt>");
                    for (ComponentParameters.Parameter parameter : component.parameters()) {
                        html.append("<dd>").append(parameter(parameter)).append("</dd>");
                    }
                }
                html.append("</dl>");
            }

            case TemplateElement.Parameter(var comp, var para) -> html.append("<dl><dt>Tapestry Parameter:</dt><dd>") //
                                                                      .append(parameter(para)) //
                                                                      .append(" of <code>") //
                                                                      .append(Strings.escapeHtml(comp)) //
                                                                      .append("</code></dd></dl>");
            case TemplateElement.Property _ -> {
                // NOOP
            }
        }

        return html.toString();
    }

    /// `<b>source</b> GridDataSource – required, literal: – The data to display.`
    private static String parameter(ComponentParameters.Parameter parameter) throws JavaModelException {
        String typeSignature = parameter.field().getTypeSignature();
        String typeName = Signature.toString(typeSignature);
        String simpleTypeName = Signature.getSimpleName(typeName);
        StringBuilder html = new StringBuilder("<b>").append(Strings.escapeHtml(parameter.name())) //
                                                     .append("</b> <code>")
                                                     .append(Strings.escapeHtml(simpleTypeName))
                                                     .append("</code>");

        if (parameter.required()) {
            html.append(" – required");
        }

        if (!parameter.defaultPrefix().equalsIgnoreCase("prop")) {
            html.append(" – default prefix <code>").append(Strings.escapeHtml(parameter.defaultPrefix())).append(":</code>");
        }

        String summary = summary(parameter.field());
        if (!summary.isEmpty()) {
            html.append(" – ").append(Strings.escapeHtml(summary));
        }

        return html.toString();
    }

    /// The first sentence of a member's Javadoc as plain text; empty if it has none.
    private static String summary(IMember member) {
        try (Reader reader = JavadocContentAccess.getHTMLContentReader(member, false, true)) {
            if (reader == null) {
                return "";
            }

            StringBuilder content = new StringBuilder();
            char[] buffer = new char[1024];
            for (int read; (read = reader.read(buffer)) > 0;) {
                content.append(buffer, 0, read);
            }

            // Block tags (@see, …) follow the description
            int blockTags = content.indexOf("<dl>");
            CharSequence description;
            if (blockTags < 0) {
                description = content;
            }
            else {
                description = content.substring(0, blockTags);
            }

            String withoutTags = TAG.matcher(description).replaceAll(" ");
            String plainText = Strings.unescapeHtml(withoutTags);
            String text = WHITESPACE.matcher(plainText).replaceAll(" ").strip();
            Matcher sentence = FIRST_SENTENCE.matcher(text);
            String summary = text;
            if (sentence.find()) {
                summary = sentence.group(1);
            }

            if (summary.length() > MAX_SUMMARY_LENGTH) {
                return summary.substring(0, MAX_SUMMARY_LENGTH) + "…";
            }

            return summary;
        }
        catch (JavaModelException | IOException _) {
            return "";
        }
    }

    /// Where JDT looks for constant values and annotations: the member's declaration.
    private static IRegion nameRegion(IMember member) throws JavaModelException {
        ISourceRange range = member.getNameRange();
        if (range != null && range.getOffset() >= 0) {
            return new Region(range.getOffset(), range.getLength());
        }

        return new Region(0, 0);
    }

    private static boolean isEnabled() {
        return TapestryPreferences.isEnabled(TapestryPreferences.TEMPLATE_HOVER);
    }

    private static boolean isTemplate(IDocument document) {
        IFile file = TemplateHyperlinkDetector.fileOf(document);
        return file != null && RelatedFiles.isTemplate(file);
    }
}
