package org.greenplum.pxf.service;

import org.apache.commons.lang.StringUtils;
import org.greenplum.pxf.api.configuration.ProtocolSettings;
import org.greenplum.pxf.api.configuration.PxfServerProperties;
import org.greenplum.pxf.api.model.ColumnDescriptor;
import org.greenplum.pxf.api.model.OutputFormat;
import org.greenplum.pxf.api.model.ProtocolHandler;
import org.greenplum.pxf.api.model.RequestContext;
import org.greenplum.pxf.api.utilities.Utilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;

/**
 * Parser for HTTP requests that contain data in HTTP headers.
 */
@Component
public class HttpRequestParser implements RequestParser<Map<String, String>> {

    private static final Logger LOG = LoggerFactory.getLogger(HttpRequestParser.class);

    private static final String TRUE_LCASE = "true";
    private static final String FALSE_LCASE = "false";
    private static final String PROFILE_SCHEME = "PROFILE-SCHEME";

    private final PxfServerProperties serverProperties;

    public HttpRequestParser(PxfServerProperties serverProperties) {
        this.serverProperties = serverProperties;
    }

    /**
     * Throws an exception when the given property value is missing in request.
     *
     * @param property missing property name
     * @throws IllegalArgumentException throws an exception with the property
     *                                  name in the error message
     */
    private static void protocolViolation(String property) {
        String error = String.format("Property %s has no value in the current request", property);
        throw new IllegalArgumentException(error);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RequestContext parseRequest(Map<String, String> request, RequestContext.RequestType requestType) {

        RequestMap params = new RequestMap(request);

        if (LOG.isDebugEnabled()) {
            // Logging only keys to prevent sensitive data to be logged
            LOG.debug("Parsing request parameters: " + params.keySet());
        }

        // build new instance of RequestContext and fill it with parsed values
        RequestContext context = new RequestContext();

        // whether we are in a Read Controller
        context.setRequestType(requestType);

        // first of all, set protocol and enrich parameters with information
        // from specified protocol
        String protocol = params.removeProperty("PROTOCOL");
        context.setProtocol(protocol);

        ProtocolSettings protocolSettings = defaultIfNull(serverProperties.getProtocolSettings().get(protocol), new ProtocolSettings());

        params.put(RequestMap.USER_PROP_PREFIX + PROFILE_SCHEME, protocolSettings.getProtocol());


        // Ext table uses system property FORMAT for wire serialization format
        String wireFormat = params.removeProperty("WIRE-FORMAT");
        context.setOutputFormat(OutputFormat.valueOf(wireFormat));

        // FDW uses user property FORMAT to indicate format of data
        String format = params.removeOptionalProperty("FORMAT");
        context.setFormat(format);

        String filterString = params.removeOptionalProperty("FILTER");
        String hasFilter = params.removeProperty("HAS-FILTER");
        if (filterString != null) {
            context.setFilterString(filterString);
        } else if ("1".equals(hasFilter)) {
            LOG.info("Original query has filter, but it was not propagated to PXF");
        }

        String fragmentIndexStr = params.removeOptionalProperty("FRAGMENT-INDEX");
        if (StringUtils.isNotBlank(fragmentIndexStr)) {
            context.setFragmentIndex(Integer.parseInt(fragmentIndexStr));
        }

        String encodedFragmentMetadata = params.removeOptionalProperty("FRAGMENT-METADATA");
        context.setFragmentMetadata(Utilities.parseBase64(encodedFragmentMetadata, "Fragment metadata information"));
        context.setHost(params.removeProperty("URL-HOST"));
        context.setMetadata(params.removeUserProperty("METADATA"));
        context.setPort(params.removeIntProperty("URL-PORT"));
        context.setProfileScheme(params.removeUserProperty(PROFILE_SCHEME));
        context.setRemoteLogin(params.removeOptionalProperty("REMOTE-USER"));
        context.setRemoteSecret(params.removeOptionalProperty("REMOTE-PASS"));
        context.setDataSource(params.removeProperty("RESOURCE"));
        context.setSegmentId(params.removeIntProperty("SEGMENT-ID"));
        context.setServerName(params.removeProperty("SERVER"));

        // An optional CONFIG value specifies the name of the server
        // configuration directory, if not provided the config is the server name
        String config = params.removeUserProperty("CONFIG");
        context.setConfig(StringUtils.isNotBlank(config) ? config : context.getServerName());

        String maxFrags = params.removeUserProperty("STATS-MAX-FRAGMENTS");
        if (!StringUtils.isBlank(maxFrags)) {
            context.setStatsMaxFragments(Integer.parseInt(maxFrags));
        }

        String sampleRatioStr = params.removeUserProperty("STATS-SAMPLE-RATIO");
        if (!StringUtils.isBlank(sampleRatioStr)) {
            context.setStatsSampleRatio(Float.parseFloat(sampleRatioStr));
        }

        String threadSafeStr = params.removeUserProperty("THREAD-SAFE");
        if (!StringUtils.isBlank(threadSafeStr)) {
            context.setThreadSafe(parseBooleanValue(threadSafeStr));
        }

        context.setTotalSegments(params.removeIntProperty("SEGMENT-COUNT"));
        context.setTransactionId(params.removeProperty("XID"));

        // parse tuple description
        parseTupleDescription(params, context);

        // parse CSV format information
        parseGreenplumCSV(params, context);

        context.setUser(params.removeProperty("USER"));

        String encodedFragmentUserData = params.removeOptionalProperty("FRAGMENT-USER-DATA");
        context.setUserData(Utilities.parseBase64(encodedFragmentUserData, "Fragment user data"));

        // Store alignment for global use as a system property
        System.setProperty("greenplum.alignment", params.removeProperty("ALIGNMENT"));

        Map<String, String> optionMappings = defaultIfNull(protocolSettings.getOptionMappings(), Collections.emptyMap());
        Map<String, String> additionalConfigProps = new HashMap<>();

        // Iterate over the remaining properties
        // we clone the keyset to prevent concurrent modification exceptions
        List<String> paramNames = new ArrayList<>(params.keySet());
        for (String param : paramNames) {
            if (StringUtils.startsWithIgnoreCase(param, RequestMap.USER_PROP_PREFIX)) {
                // Add all left-over user properties as options
                String optionName = param.toLowerCase().replace(RequestMap.USER_PROP_PREFIX_LOWERCASE, "");
                String optionValue = params.removeUserProperty(optionName);
                context.addOption(optionName, optionValue);
                LOG.debug("Added option {} to request context", optionName);

                // lookup if the option should also be applied as a config property
                String propertyName = optionMappings.get(optionName);
                if (StringUtils.isNotBlank(propertyName)) {
                    // if option has been provided by the user in the request, set the value
                    // of the corresponding configuration property
                    if (optionValue != null) {
                        additionalConfigProps.put(propertyName, optionValue);
                        // do not log property value as it might contain sensitive information
                        LOG.debug("Added extra config property {} from option {}", propertyName, optionName);
                    }
                }
            } else if (StringUtils.startsWithIgnoreCase(param, RequestMap.PROP_PREFIX)) {
                // log debug for all left-over system properties
                LOG.debug("Unused property {}", param);
            }
        }

        context.setAdditionalConfigProps(additionalConfigProps);
        context.setProtocolSettings(protocolSettings);

        // Call the protocol handler for any protocol-specific logic handling
        String handlerClassName = protocolSettings.getHandler();
        if (StringUtils.isNotBlank(handlerClassName)) {
            // TODO: fix this
//            Class<?> clazz;
//            try {
//                clazz = Class.forName(handlerClassName);
//                ProtocolHandler handler = (ProtocolHandler) clazz.getDeclaredConstructor().newInstance();
//            } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
//                throw new RuntimeException(String.format("Error when invoking handlerClass '%s' : %s", handlerClassName, e), e);
//            }
        }

        // validate that the result has all required fields, and values are in valid ranges
        context.validate();

        return context;
    }

    private void parseGreenplumCSV(RequestMap params, RequestContext context) {
        context.getGreenplumCSV()
            .withDelimiter(params.removeUserProperty("DELIMITER"))
            .withEscapeChar(params.removeUserProperty("ESCAPE"))
            .withNewline(params.removeUserProperty("NEWLINE"))
            .withQuoteChar(params.removeUserProperty("QUOTE"))
            .withValueOfNull(params.removeUserProperty("NULL"));
    }

    private boolean parseBooleanValue(String booleanStr) {

        if (booleanStr.equalsIgnoreCase(TRUE_LCASE)) {
            return true;
        }
        if (booleanStr.equalsIgnoreCase(FALSE_LCASE)) {
            return false;
        }
        throw new IllegalArgumentException("Illegal boolean value '"
            + booleanStr + "'." + " Usage: [TRUE|FALSE]");
    }

    /*
     * Sets the tuple description for the record
     * Attribute Projection information is optional
     */
    private void parseTupleDescription(RequestMap params, RequestContext context) {
        int columns = params.removeIntProperty("ATTRS");
        BitSet attrsProjected = new BitSet(columns + 1);

        /* Process column projection info */
        String columnProjStr = params.removeOptionalProperty("ATTRS-PROJ");
        if (columnProjStr != null) {
            int numberOfProjectedColumns = Integer.parseInt(columnProjStr);
            context.setNumAttrsProjected(numberOfProjectedColumns);
            if (numberOfProjectedColumns > 0) {
                String[] projectionIndices = params.removeProperty("ATTRS-PROJ-IDX").split(",");
                for (String s : projectionIndices) {
                    attrsProjected.set(Integer.valueOf(s));
                }
            } else {
                /* This is a special case to handle aggregate queries not related to any specific column
                 * eg: count(*) queries. */
                attrsProjected.set(0);
            }
        }


        for (int attrNumber = 0; attrNumber < columns; attrNumber++) {
            String columnName = params.removeProperty("ATTR-NAME" + attrNumber);
            int columnOID = params.removeIntProperty("ATTR-TYPECODE" + attrNumber);
            String columnTypeName = params.removeProperty("ATTR-TYPENAME" + attrNumber);
            Integer[] columnTypeMods = parseTypeMods(params, attrNumber);
            // Project the column if columnProjStr is null
            boolean isProjected = columnProjStr == null || attrsProjected.get(attrNumber);
            ColumnDescriptor column = new ColumnDescriptor(
                columnName,
                columnOID,
                attrNumber,
                columnTypeName,
                columnTypeMods,
                isProjected);
            context.getTupleDescription().add(column);

            if (columnName.equalsIgnoreCase(ColumnDescriptor.RECORD_KEY_NAME)) {
                context.setRecordkeyColumn(column);
            }
        }
    }

    private Integer[] parseTypeMods(RequestMap params, int columnIndex) {
        String typeModCountPropName = "ATTR-TYPEMOD" + columnIndex + "-COUNT";
        String typeModeCountStr = params.removeOptionalProperty(typeModCountPropName);
        if (typeModeCountStr == null)
            return null;

        int typeModeCount = parsePositiveIntOrError(typeModeCountStr, typeModCountPropName);

        Integer[] result = new Integer[typeModeCount];
        for (int i = 0; i < typeModeCount; i++) {
            String typeModItemPropName = "ATTR-TYPEMOD" + columnIndex + "-" + i;
            result[i] = parsePositiveIntOrError(params.removeProperty(typeModItemPropName), typeModItemPropName);
        }
        return result;
    }

    private int parsePositiveIntOrError(String s, String propName) {
        int n;
        try {
            n = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("%s must be an integer", propName), e);
        }
        if (n < 0) {
            throw new IllegalArgumentException(String.format("%s must be a positive integer", propName));
        }
        return n;
    }

    /**
     * Converts the request headers multivalued map to a case-insensitive
     * regular map by taking only first values and storing them in a
     * CASE_INSENSITIVE_ORDER TreeMap. All values are converted from ISO_8859_1
     * (ISO-LATIN-1) to UTF_8.
     */
    static class RequestMap extends TreeMap<String, String> {
        private static final String PROP_PREFIX = "X-GP-";
        private static final String USER_PROP_PREFIX = "X-GP-OPTIONS-";
        private static final String USER_PROP_PREFIX_LOWERCASE = "x-gp-options-";
        private static final String ENCODED_HEADER_VALUES_NAME = PROP_PREFIX + "ENCODED-HEADER-VALUES";

        RequestMap(Map<String, String> requestHeaders) {
            super(String.CASE_INSENSITIVE_ORDER);

            boolean decodeHeaderValue = requestHeaders
                .entrySet()
                .stream()
                .filter(entry -> StringUtils.equalsIgnoreCase(ENCODED_HEADER_VALUES_NAME, entry.getKey()))
                .map(entry -> StringUtils.equalsIgnoreCase("true", entry.getValue()))
                .findFirst()
                .orElse(false);

            for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                String value = getValue(entry.getValue());
                if (value == null) continue;
                String key = entry.getKey();
                if (decodeHeaderValue && StringUtils.startsWithIgnoreCase(key, PROP_PREFIX)) {
                    try {
                        value = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
                    } catch (UnsupportedEncodingException e) {
                        throw new RuntimeException(String.format("Error while URL decoding value '%s'", value), e);
                    }
                }
                LOG.trace("Key: {} Value: {}", key, value);
                put(key, value.replace("\\\"", "\""));
            }
        }

        /**
         * Returns the value from the list of values. If the list has 1 element,
         * it returns the element. If the list has more than one element, it
         * returns a flattened string joined with a comma.
         *
         * @param value the value
         * @return the value
         */
        private String getValue(String value) {
            if (value == null) return null;
            // Converting to value UTF-8 encoding
            return new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        }

        /**
         * Returns a user defined property.
         *
         * @param userProp the lookup user property
         * @return property value as a String
         */
        private String removeUserProperty(String userProp) {
            return remove(USER_PROP_PREFIX + userProp);
        }

        /**
         * Returns the optional property value. Unlike {@link #removeProperty}, it will
         * not fail if the property is not found. It will just return null instead.
         *
         * @param property the lookup optional property
         * @return property value as a String
         */
        private String removeOptionalProperty(String property) {
            return remove(PROP_PREFIX + property);
        }

        /**
         * Returns a property value as an int type and removes it from the map
         *
         * @param property the lookup property
         * @return property value as an int type
         * @throws NumberFormatException if the value is missing or can't be
         *                               represented by an Integer
         */
        private int removeIntProperty(String property) {
            return Integer.parseInt(removeProperty(property));
        }

        /**
         * Returns the value to which the specified property is mapped and
         * removes it from the map
         *
         * @param property the lookup property key
         * @throws IllegalArgumentException if property key is missing
         */
        private String removeProperty(String property) {
            String result = remove(PROP_PREFIX + property);

            if (result == null) {
                protocolViolation(property);
            }

            return result;
        }

        /**
         * Returns a property value as boolean type. A boolean property is defined
         * as an int where 0 means false, and anything else true (like C).
         *
         * @param property the lookup property
         * @return property value as boolean
         * @throws NumberFormatException if the value is missing or can't be
         *                               represented by an Integer
         */
        private boolean removeBoolProperty(String property) {
            return removeIntProperty(property) != 0;
        }
    }

}
