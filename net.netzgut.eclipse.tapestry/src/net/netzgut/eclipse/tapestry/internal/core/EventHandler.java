package net.netzgut.eclipse.tapestry.internal.core;

/// A handler method that could be added to a page/component class.
///
/// @param componentId `null` for handlers without "From" (anonymous components)
/// @param componentType for display only, may be `null`
/// @param templateName `null` for page events not declared in a template (`activate`, `passivate`)
/// @param returnType `void`, `String` or `Object`
/// @param eventConstant name of the `EventConstants` field for `event`, or `null`
public record EventHandler(String methodName,
                           String event,
                           String eventConstant,
                           String componentId,
                           String componentType,
                           String returnType,
                           String templateName)
    implements
        HandlerMethod {
}
