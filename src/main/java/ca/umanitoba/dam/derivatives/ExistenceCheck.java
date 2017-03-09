package ca.umanitoba.dam.derivatives;

import java.io.File;

import org.apache.camel.Handler;
import org.apache.camel.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filter for if a file exists
 * @author whikloj
 * @since 2016-09-14
 */
public class ExistenceCheck {
    private static Logger LOGGER = LoggerFactory.getLogger(ExistenceCheck.class);

    /**
     * Check for the existence of a file.
     *
     * @param fullPath
     *      The full path to the file
     * @return boolean
     *      If file exists
     */
    @Handler
    public boolean fileExists(@Header("checkFile") final String fullPath) {
        final File tmpFile = new File(fullPath);
        final boolean exists = tmpFile.exists();
        LOGGER.debug("check if {} does exist, returning {}", fullPath, (exists ? "true" : "false"));
        return exists;
    }
    
    /**
     * Check for the non-existence of a file.
     *
     * @param fullPath
     *      The full path to the file
     * @return boolean
     *      If file DOESN'T exist
     */
    @Handler
    public boolean fileNotExists(@Header("checkFile") final String fullPath) {
        final File tmpFile = new File(fullPath);
        final boolean exists = tmpFile.exists();
        LOGGER.debug("checking if {} does exist, returning {}", fullPath, (exists ? "true" : "false"));
        return (!exists);        
    }
}
