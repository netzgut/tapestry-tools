package net.netzgut.eclipse.tapestry.internal.core;

import java.util.List;
import java.util.Set;

/// A component as declared in a template.
///
/// @param id component id (`t:id`), or `null` for anonymous components
/// @param type component type as written (`form`, `layout/Header`, `lib/Foo`), or `null` if only
///            declared in the class (`<form t:id="login">` with `@Component Form login`)
/// @param mixins mixin types from `t:mixins`, without ordering constraints
/// @param explicitEvents literal values of an `event` parameter, e.g. on EventLink or Submit
public record ComponentUsage(String id, //
                             String type,
                             List<String> mixins,
                             Set<String> explicitEvents) {
}
