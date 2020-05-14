package org.greenplum.pxf.service.bridge;

import org.apache.hadoop.conf.Configuration;
import org.greenplum.pxf.api.model.Accessor;
import org.greenplum.pxf.api.model.RequestContext;
import org.greenplum.pxf.api.model.Resolver;
import org.greenplum.pxf.api.utilities.AccessorFactory;
import org.greenplum.pxf.api.utilities.ResolverFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract class representing the bridge that provides to subclasses logger and accessor and
 * resolver instances obtained from the factories.
 */
public abstract class BaseBridge implements Bridge {

    protected final Logger LOG = LoggerFactory.getLogger(this.getClass());

    private final AccessorFactory accessorFactory;
    private final ResolverFactory resolverFactory;

    protected Accessor accessor;
    protected Resolver resolver;

    /**
     * Creates a new instance for a given request context. Uses provides instances of
     * plugin factories to request accessor and resolver.
     *
     * @param accessorFactory accessor factory
     * @param resolverFactory resolver factory
     */
    BaseBridge(AccessorFactory accessorFactory, ResolverFactory resolverFactory) {
        this.accessorFactory = accessorFactory;
        this.resolverFactory = resolverFactory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(RequestContext context, Configuration configuration) {
        this.accessor = accessorFactory.getPlugin(context, configuration);
        this.resolver = resolverFactory.getPlugin(context, configuration);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isThreadSafe() {
        boolean result = accessor.isThreadSafe() && resolver.isThreadSafe();
        LOG.debug("Bridge is {}thread safe", (result ? "" : "not "));
        return result;
    }
}
