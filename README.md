# Tapestry Tools for Eclipse

Lightweight [Apache Tapestry](https://tapestry.apache.org/) support for Eclipse: navigation between page and component classes and their resources, refactoring that keeps them together, hover and validation in templates, and content assist in Java and templates.

It works on your sources and the Java model only.
There is no Tapestry runtime, registry or project nature involved, so it works with any Maven or Gradle project.
Both the `jakarta` and `javax` Tapestry variant are supported.

## Requirements

*   Eclipse 2026-09 (4.41) or later, running on Java 25
*   Apache Tapestry 5.9 or later
*   WTP XML editor or the Generic Editor for your templates

## Installation

1.  *Help > Install New Software...*
2.  *Add...* the update site `https://netzgut.github.io/tapestry-tools/`
3.  Select _Tapestry Tools_ and finish the wizard.
    Releases are PGP-signed: on the first install, Eclipse asks whether to trust the signing key.
    Its fingerprint is `2965 0907 B006 5376 4FDE  2A24 43F3 9FDF 926D C24B`.

`.tml` files are associated with the XML content type, so whatever XML editor you have opens them.

## Features

### Navigation

*   **Switch between class and template:**
    *   <kbd>Cmd</kbd>+<kbd>Ctrl</kbd>+<kbd>T</kbd> on macOS
    *   <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Y</kbd> on Linux/Windows
    *   Menu entry under _Navigate_
    *   If a page or component has no template yet, it offers to create one.

*   **Quick Switch:**\
    A filterable popup with the class, templates, message catalogs, scripts, stylesheets and overrides of a page or component.
    *   <kbd>Cmd</kbd>+<kbd>Ctrl</kbd>+<kbd>E</kbd> on macOS
    *   <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>E</kbd> on Linux/Window
    *   Missing templates and catalogs are listed too, and get created when chosen.

*   **Cmd/Ctrl+click in templates:**
    *   Component types and mixins open their class
    *   `page="..."` the page
    *   `t:id` the field and event handlers
    *   Parameters their `@Parameter` field
    *   `message:` keys their definition
    *   Property expressions (`${user.name}`, `value="user.name"`) the getter, field or method.

*   **Cmd/Ctrl+click in Java:**
    *   Component fields and event handlers open their element in the template
    *   `messages.get("key")` the key's definition

### Refactoring

Renaming or moving a page, component or mixin class, or one of their packages, also renames/moves the related templates, message catalogs and assets, including locale variants and overrides.

### Content assist

*   **Templates:**
    *   Component and mixin names
    *   Pages
    *   Message keys
    *   Component parameters (required ones first)
    *   Property expressions (through the page or component class and its properties)

*   **Java:**
    *   Event handler methods for the components in the template (e.g., `onSuccessFromLoginForm`)
    *   Page activation
    *   Render phase and page lifecycle methods
    *   Message keys in `messages.get("...")`

### Hover

In templates, hovering a component, mixin or page shows its Javadoc and parameters, a parameter its field's Javadoc, and a property expression the getter's, field's or method's.

### Validation

Templates get problem markers for:

*   Unknown component types, mixins and pages
*   Parameters the component doesn't declare (unless `@SupportInformalParameters`)
*   `message:` keys that are in no related catalog
*   Optionally, component ids without a field or event handler

Templates are checked when saved, and all templates of a project when its classes or catalogs change.

## Preferences

See _Preferences > Tapestry Tools_ and _Java > Editor > Content Assist > Advanced_.

## Building

You need Java 25 and Maven 3.9 or later.

```sh
mvn clean verify  # or: just build
```

This runs the tests in a headless workbench and produces the update site in `net.netzgut.eclipse.tapestry.repository/target/repository`.
Add that folder as a local update site to try a build.
Every build replaces it, so _Reload_ the site under _Available Software Sites_ before checking for updates.

For development, open the projects in an Eclipse with PDE and set `target-platform/tapestry.target` as the active target platform.

## Releasing

Releases are built, PGP-signed and published.
You need [just](https://just.systems/), GnuPG with your signing key, and a clean `main` that is pushed to `origin`.

```sh
just release 0.1.0
```

This checks that `pom.xml` is at `0.1.0-SNAPSHOT` and `v0.1.0` doesn't exist yet.
It then builds and tests with `mvn -Prelease`, which signs the update site with the release signing key set in the `justfile`.
`gpg-agent` asks for the passphrase.

The site is committed to the `gh-pages` branch, and `main` is tagged `v0.1.0`.
Both are pushed only after confirmation.

Afterwards, raise the version for the next release and commit it:

```sh
just set-version 0.2.0-SNAPSHOT
```

## AI Disclosure

This project was built with the help of Claude.
I had no prior experience with developing Eclipse plugins, and the documentation is... let's say "not that good".
That's why AI can bridge the gap to get the project up and running and help with implementing the details.
It's a tool, and should be used as such, not as a replacement for the full process.

## License

[Apache License 2.0](LICENSE), see also [NOTICE](NOTICE).

Apache, Apache Tapestry and Tapestry are trademarks of The Apache Software Foundation. This project is not
affiliated with or endorsed by it.
