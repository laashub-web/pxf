package org.greenplum.pxf.service;

import org.greenplum.pxf.api.configuration.PxfServerProperties;
import org.greenplum.pxf.service.rest.Version;
import org.greenplum.pxf.service.servlet.SecurityServletFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the registerSecurityServletFilter bean method to be processed by
 * the Spring container
 */
@Configuration
@EnableConfigurationProperties(PxfServerProperties.class)
public class PxfConfiguration {

    private final ApplicationContext applicationContext;

    public PxfConfiguration(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Returns a {@link FilterRegistrationBean} that registers the
     * {@link SecurityServletFilter} for URL patterns that match
     * /pxf/{protocol_version}/*
     *
     * @return the {@link FilterRegistrationBean} for the {@link SecurityServletFilter}
     */
    @Bean
    public FilterRegistrationBean<SecurityServletFilter> registerSecurityServletFilter() {
        FilterRegistrationBean<SecurityServletFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(applicationContext.getBean(SecurityServletFilter.class));
        registrationBean.addUrlPatterns("/pxf/" + Version.PXF_PROTOCOL_VERSION + "/*");
        return registrationBean;
    }
}
