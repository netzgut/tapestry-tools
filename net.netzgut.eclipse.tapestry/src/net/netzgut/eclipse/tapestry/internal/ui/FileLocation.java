package net.netzgut.eclipse.tapestry.internal.ui;

import org.eclipse.core.resources.IFile;

/// A text range in a file, e.g. a `t:id` value in a template or a key in a message catalog.
record FileLocation(IFile file, //
                    int offset,
                    int length,
                    int line) {

    /// 1-based line of `offset` in `content`.
    static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
