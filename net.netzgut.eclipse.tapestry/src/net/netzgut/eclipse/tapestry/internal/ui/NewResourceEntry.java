package net.netzgut.eclipse.tapestry.internal.ui;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.IType;

import net.netzgut.eclipse.tapestry.internal.core.NewResource;

/// A quick switch entry for a template or message catalog that doesn't exist yet.
///
/// @param file where it will be created
record NewResourceEntry(IType type, //
                        NewResource.Kind kind,
                        IFile file) {
}
