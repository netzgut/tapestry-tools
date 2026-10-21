package net.netzgut.eclipse.tapestry.internal.core;

import java.util.List;

import org.eclipse.core.resources.IFile;

/// A key available to a class's `Messages`, with its value in every catalog defining it.
///
/// @param definitions most specific first: the class's own plain catalog, then locales/variants,
///            overrides, then superclasses
public record MessageKey(String key, //
                         List<Definition> definitions) {

    public record Definition(IFile file, String value) {
    }

    /// The value of the most specific catalog, usually the default language.
    public String value() {
        return this.definitions.get(0).value();
    }
}
