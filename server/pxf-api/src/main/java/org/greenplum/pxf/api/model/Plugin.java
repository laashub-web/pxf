package org.greenplum.pxf.api.model;

/**
 * Base interface for all plugin types that provides information on plugin thread safety
 */
public interface Plugin {

    /**
     * Sets the context for the current request
     *
     * @param context the context for the current request
     */
    void setRequestContext(RequestContext context);

    /**
     * Checks if the plugin is thread safe
     *
     * @return true if plugin is thread safe, false otherwise
     */
    default boolean isThreadSafe() {
        return true;
    }
}
