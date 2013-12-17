package wshttpserver;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Denotes that the specified method is safe to be called by multiple threads at the same time.
 * If specified on an interface / abstract method, it denotes that any implementation must be thread safe.
 * @author Joris
 */
@Documented
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.SOURCE)
public @interface ThreadSafe
{
        
}
