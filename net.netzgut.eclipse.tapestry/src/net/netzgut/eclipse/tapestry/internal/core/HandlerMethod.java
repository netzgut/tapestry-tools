package net.netzgut.eclipse.tapestry.internal.core;

/// A method Tapestry calls by convention that content assist can generate.
public sealed interface HandlerMethod permits EventHandler, LifecycleMethod {

    String methodName();

    String returnType();

    default boolean returnsVoid() {
        return "void".equals(returnType());
    }
}
