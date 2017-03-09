package ca.umanitoba.dam.derivatives;

import static org.slf4j.LoggerFactory.getLogger;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;


public class Activator implements BundleActivator {

    private static final Logger logger = getLogger(Activator.class);

    @Override
    public void start(BundleContext context) throws Exception {
        logger.info("We have started our bundle");

    }

    @Override
    public void stop(BundleContext context) throws Exception {
        logger.info("We have stopped our bundle");

    }

}
