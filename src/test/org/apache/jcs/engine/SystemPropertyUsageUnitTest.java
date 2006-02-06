package org.apache.jcs.engine;

import java.util.Properties;

import junit.framework.TestCase;

import org.apache.jcs.JCS;
import org.apache.jcs.engine.control.CompositeCacheManager;
import org.apache.jcs.utils.props.PropertyLoader;

/**
 * Verify that system properties can override.
 */
public class SystemPropertyUsageUnitTest
    extends TestCase
{

    /**
     * Verify that the system properties are used.
     * @throws Exception
     *  
     */
    public void testSystemPropertyUsage()
        throws Exception
    {
        System.getProperties().setProperty( "jcs.default.cacheattributes.MaxObjects", "6789" );

        JCS.setConfigFilename( "/TestSystemPropertyUsage.ccf" );

        JCS jcs = JCS.getInstance( "someCacheNotInFile" );

        assertEquals( "System property value is not reflected", jcs.getCacheAttributes().getMaxObjects(), Integer
            .parseInt( "6789" ) );

    }

    /**
     * Verify that the system properties are not used is specified.
     * 
     * @throws Exception
     *  
     */
    public void testSystemPropertyUsage_inactive()
        throws Exception
    {
        System.getProperties().setProperty( "jcs.default.cacheattributes.MaxObjects", "6789" );

        CompositeCacheManager mgr = CompositeCacheManager.getUnconfiguredInstance();

        Properties props = PropertyLoader.loadProperties( "TestSystemPropertyUsage.ccf" );

        mgr.configure( props, false );

        JCS jcs = JCS.getInstance( "someCacheNotInFile" );

        assertFalse( "System property value should not be reflected",
                     jcs.getCacheAttributes().getMaxObjects() == Integer.parseInt( props
                         .getProperty( "jcs.default.cacheattributes.MaxObjects" ) ) );

    }
}