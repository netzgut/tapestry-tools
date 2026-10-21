package net.netzgut.eclipse.tapestry.internal.core;

import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;

/// Recognizes the key argument of `messages.get("...")`, `format("...", ...)` and `contains("...")`
public final class MessageCalls {

    public static final String MESSAGES = "org.apache.tapestry5.commons.Messages";

    private static final Set<String> METHODS = Set.of("get", "format", "contains");

    /// @param start offset of the key's first character, right after the opening quote
    /// @param length length of the key as written, without quotes
    /// @param key the key's value
    /// @param prefix the part of the key before the cursor
    public record KeyArgument(int start, //
                              int length,
                              String key,
                              String prefix) {
    }

    private MessageCalls() {
    }

    /// The message key argument at `offset`, or `null`.
    ///
    /// Parses the whole unit with bindings, so callers should check the text first.
    /// (`setFocalPosition`, which skips other method bodies, dropped the body containing the
    /// position in tests, so it is not used.)
    public static KeyArgument keyArgumentAt(ICompilationUnit unit, int offset, IProgressMonitor monitor) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setStatementsRecovery(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);
        return keyArgumentAt(ast, offset);
    }

    /// Like [int, IProgressMonitor)][#keyArgumentAt(ICompilationUnit,], on an existing AST
    /// with bindings.
    public static KeyArgument keyArgumentAt(CompilationUnit ast, int offset) {
        ASTNode node = NodeFinder.perform(ast, offset, 0);
        if (!(node instanceof StringLiteral literal)
            || !(literal.getParent() instanceof MethodInvocation call)
            || call.arguments().isEmpty()
            || call.arguments().get(0) != literal
            || !METHODS.contains(call.getName().getIdentifier())
            || !isMessages(call.getExpression())) {
            return null;
        }

        int start = literal.getStartPosition() + 1;
        if (offset < start || offset > literal.getStartPosition() + literal.getLength() - 1) {
            return null;
        }

        String source = literal.getEscapedValue();
        String prefix = source.substring(1, offset - literal.getStartPosition());
        return new KeyArgument(start,
                               literal.getLength() - 2,
                               literal.getLiteralValue(),
                               prefix);
    }

    /// By type, or by name if the type cannot be resolved (Tapestry missing from the classpath
    /// while editing).
    private static boolean isMessages(Expression expression) {
        if (expression == null) {
            return false;
        }

        ITypeBinding type = expression.resolveTypeBinding();
        if (type != null && !type.isRecovered()) {
            return isMessages(type);
        }

        return expression instanceof SimpleName name && name.getIdentifier().equalsIgnoreCase("messages");
    }

    private static boolean isMessages(ITypeBinding type) {
        if (type.getErasure().getQualifiedName().equals(MESSAGES)) {
            return true;
        }

        for (ITypeBinding superInterface : type.getInterfaces()) {
            if (isMessages(superInterface)) {
                return true;
            }
        }

        return false;
    }
}
