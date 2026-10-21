package net.netzgut.eclipse.tapestry.internal.core;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IType;

/// The message keys a page/component can use: from its own catalogs (`Index.properties`,
/// `Index_de.properties`, overrides) and those of its superclasses, which Tapestry merges in. The
/// application-wide catalog is not included, as its location depends on the application's setup.
public final class MessageCatalogs {

    private static final String EXTENSION        = "properties";
    private static final int    MAX_SUPERCLASSES = 10;

    private record Parsed(long modificationStamp, //
                          Map<String, String> entries) {
    }

    private static final Map<IPath, Parsed> CATALOGS = new ConcurrentHashMap<>();

    public static boolean isCatalog(IFile file) {
        return EXTENSION.equalsIgnoreCase(file.getFileExtension());
    }

    /// All keys, sorted by name.
    public static List<MessageKey> keys(IType type) throws CoreException {
        Map<String, List<MessageKey.Definition>> definitions = new LinkedHashMap<>();
        IType current = RelatedFiles.topLevel(type);
        for (int depth = 0; current != null && depth < MAX_SUPERCLASSES; depth++) {
            for (IFile catalog : catalogs(current)) {
                Map<String, String> catalogEntries = entries(catalog);
                for (Map.Entry<String, String> entry : catalogEntries.entrySet()) {
                    definitions.computeIfAbsent(entry.getKey(), _ -> new ArrayList<>())
                               .add(new MessageKey.Definition(catalog, entry.getValue()));
                }
            }

            current = superclass(current);
        }

        List<MessageKey> keys = new ArrayList<>();
        definitions.forEach((key, values) -> keys.add(new MessageKey(key, List.copyOf(values))));
        keys.sort(Comparator.comparing(MessageKey::key));

        return keys;
    }

    /// Where `key` is defined, most specific first; empty if nowhere.
    public static List<MessageKey.Definition> definitions(IType type, String key) throws CoreException {
        List<MessageKey> keys = keys(type);
        for (MessageKey candidate : keys) {
            if (candidate.key().equals(key)) {
                return candidate.definitions();
            }
        }

        return List.of();
    }

    /// Position of a key in a catalog.
    public record KeyPosition(int offset, //
                              int length,
                              int line) {
    }

    /// The line defining `key` in `catalog`, or `null`
    public static KeyPosition locate(IFile catalog, //
                                     String key) throws CoreException {
        String content = TemplateLocator.read(catalog, "UTF-8");
        Pattern definitionPattern =
            Pattern.compile("^[ \\t\\f]*(" + Pattern.quote(key) + ")(?=[ \\t\\f]*[=:]|[ \\t\\f]|$)", Pattern.MULTILINE);
        Matcher definition = definitionPattern.matcher(content);
        if (!definition.find()) {
            return null;
        }

        int offset = definition.start(1);
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }

        return new KeyPosition(offset, key.length(), line);
    }

    /// Most specific first: `Index.properties`, then suffixed ones by name, overrides last.
    private static List<IFile> catalogs(IType type) throws CoreException {
        List<IFile> found = RelatedFiles.find(type, MessageCatalogs::isCatalog);
        List<IFile> catalogs = new ArrayList<>(found);
        catalogs.sort(Comparator.comparing(MessageCatalogs::isOverride)
                                .thenComparing(file -> file.getName().toLowerCase(Locale.ROOT)));
        return catalogs;
    }

    private static boolean isOverride(IFile file) {
        try {
            IContainer root = RelatedFiles.sourceFolderOf(file);
            return root != null && RelatedFiles.isInOverrideFolder(root, file);
        }
        catch (CoreException _) {
            return false;
        }
    }

    private static Map<String, String> entries(IFile catalog) throws CoreException {
        IPath path = catalog.getFullPath();
        long stamp = catalog.getModificationStamp();
        Parsed parsed = CATALOGS.get(path);
        if (parsed == null || parsed.modificationStamp() != stamp) {
            Properties properties = new Properties();
            try {
                // Tapestry reads catalogs as UTF-8, whatever encoding Eclipse assumes for .properties files
                String content = TemplateLocator.read(catalog, "UTF-8");
                properties.load(new StringReader(content));
            }
            catch (IOException | IllegalArgumentException e) {
                // malformed unicode escape while editing; offer what we have
            }

            Map<String, String> entries = new LinkedHashMap<>();
            properties.stringPropertyNames().forEach(key -> entries.put(key, properties.getProperty(key)));
            parsed = new Parsed(stamp, entries);

            CATALOGS.put(path, parsed);
        }

        return parsed.entries();
    }

    private static IType superclass(IType type) throws CoreException {
        String name = type.getSuperclassName();
        if (name == null) {
            return null;
        }

        int generics = name.indexOf('<');
        if (generics >= 0) {
            name = name.substring(0, generics);
        }
        IType superclass = JavaSources.resolveType(type.getJavaProject(), type, name);

        // Tapestry's own base classes have no catalogs worth offering
        if (superclass == null || superclass.getFullyQualifiedName().startsWith("java.")) {
            return null;
        }

        return superclass;
    }

    private MessageCatalogs() {
    }
}
