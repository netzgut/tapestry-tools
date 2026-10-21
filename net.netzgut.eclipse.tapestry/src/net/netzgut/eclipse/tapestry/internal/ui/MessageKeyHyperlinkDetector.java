package net.netzgut.eclipse.tapestry.internal.ui;

import java.util.List;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.manipulation.SharedASTProviderCore;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.hyperlink.AbstractHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

import net.netzgut.eclipse.tapestry.internal.core.MessageCalls;

/// Cmd/Ctrl+click on the key in `messages.get("title")` opens its definition in the class's
/// catalog.
///
/// Uses the AST the Java editor keeps after each reconcile ([SharedASTProviderCore]) instead of
/// parsing, since detection runs on the UI thread on every mouse move. Without a current AST there
/// is simply no link this time.
public class MessageKeyHyperlinkDetector extends AbstractHyperlinkDetector {

    @Override
    public IHyperlink[] detectHyperlinks(ITextViewer viewer, IRegion region, boolean canShowMultipleHyperlinks) {
        ITextEditor editor = getAdapter(ITextEditor.class);
        if (editor == null) {
            return null;
        }

        IEditorInput input = editor.getEditorInput();
        if (!(JavaUI.getEditorInputTypeRoot(input) instanceof ICompilationUnit unit)) {
            return null;
        }

        CompilationUnit ast = SharedASTProviderCore.getAST(unit, SharedASTProviderCore.WAIT_NO, null);
        if (ast == null) {
            return null;
        }

        int offset = region.getOffset();
        MessageCalls.KeyArgument argument = MessageCalls.keyArgumentAt(ast, offset);
        if (argument == null || argument.key().isEmpty()) {
            return null;
        }

        try {
            if (!(unit.getElementAt(offset) instanceof IJavaElement element)
                || !(element.getAncestor(IJavaElement.TYPE) instanceof IType type)) {
                return null;
            }

            Region keyRegion = new Region(argument.start(), argument.length());
            return new IHyperlink[] { new MessageKeyHyperlink(_ -> List.of(type), argument.key(), keyRegion) };
        }
        catch (JavaModelException _) {
            return null;
        }
    }
}
