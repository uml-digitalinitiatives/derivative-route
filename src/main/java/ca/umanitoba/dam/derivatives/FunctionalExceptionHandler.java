package ca.umanitoba.dam.derivatives;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handle exceptions in a standard way
 * @author whikloj
 * @since 2016-09-14
 */
public class FunctionalExceptionHandler implements Processor {

    private static Logger logger = LoggerFactory.getLogger(FunctionalExceptionHandler.class);

    @Override
    public void process(Exchange exchange) throws Exception {
        Throwable caused = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        if (caused != null) {
            // here you can do what you want, but Camel regard this exception as handled, and
            // this processor as a failurehandler, so it wont do redeliveries. So this is the
            // end of this route. But if we want to route it somewhere we can just get a
            // producer template and send it.
            logger.error("Received exception ({}) with message ({})", caused.getClass().getName(), caused.getMessage());
            caused.printStackTrace();
        }
        // send it to our mock endpoint
    }

}
