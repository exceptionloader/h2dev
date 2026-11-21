package dev.exceptionloader.reports.common.inject;

import jakarta.inject.Qualifier;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the logger of the owning plugin. Guice binds an unqualified {@code java.util.logging.Logger}
 * on its own, so the plugin logger needs a qualifier.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface PluginLogger {
}
