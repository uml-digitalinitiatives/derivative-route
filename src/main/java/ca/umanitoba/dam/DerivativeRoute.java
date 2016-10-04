package ca.umanitoba.dam;

import static org.slf4j.LoggerFactory.getLogger;
import static org.apache.camel.component.exec.ExecBinding.EXEC_COMMAND_ARGS;
import static org.apache.camel.component.exec.ExecBinding.EXEC_COMMAND_WORKING_DIR;
import static org.apache.camel.component.exec.ExecBinding.EXEC_EXIT_VALUE;
import static org.apache.camel.component.exec.ExecBinding.EXEC_STDERR;
import static org.apache.camel.LoggingLevel.DEBUG;
import static org.apache.camel.LoggingLevel.INFO;
import static org.apache.camel.LoggingLevel.ERROR;
import static org.apache.camel.LoggingLevel.TRACE;

import org.apache.camel.PropertyInject;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.exec.ExecException;
import org.slf4j.Logger;

/**
 * Create derivatives for Tiffs or PDFs add to source.directory
 * @author whikloj
 * @since 2016-09-12
 */
public class DerivativeRoute extends RouteBuilder {

    private static final Logger logger = getLogger(DerivativeRoute.class);
    
    @PropertyInject(value = "source.density")
    private int density;
    
    @PropertyInject(value = "source.directory")
    private String directory;
    
    @Override
    public void configure() throws Exception {
        
        final int adjustedDensity  = (int)(density * 1.25);
        
        logger.debug("source.directory is {}", this.directory);
        
        /**
         * How to handle exceptions with exec:
         */
        onException(ExecException.class)
        .process(new FunctionalExceptionHandler())
        .maximumRedeliveries(3)
        .handled(false);
        
        /**
         * Listen for HOCR.hocr and move to HOCR.html
         */
        from("file:{{source.directory}}?recursive=true&include=HOCR.hocr&move=$simple{file:name.noext}.html")
        .description("HOCR.hocr to HOCR.html route")
        .log(DEBUG, "Move HOCR.hocr to ${header.CamelFileParent}/HOCR.html");
        
        /**
         * Listen for a file HOCR.txt in this directory
         */
        from("file:{{source.directory}}?recursive=true&include=HOCR.txt&move=OCR.txt")
        .description("HOCR.txt listener")
        .log(DEBUG, "Move HOCR.txt to ${header.CamelFileParent}/OCR.txt");
        
        /**
         * Listen for OBJ.tiff and process
         */
        from("file:{{source.directory}}?recursive=true&include=OBJ.tiff&noop=true&readLock=fileLock")
        .description("OBJ.tiff file listener")
        .log(DEBUG, "Got a tiff in the source.directory")
        .setProperty("tiffFile", simple("${header.CamelFileAbsolutePath}"))
        .setProperty("workingDir", simple("${header.CamelFileParent}"))
        .to("seda:processTiff");
        
        /**
         * Listen for a PDF in this directory
         */
        from("file:{{source.directory}}?recursive=true&noop=true&include=PDF.pdf&readLock=fileLock")
        .description("Process PDF/Tiff files")
        .log(INFO, "Process PDF ${file:name}")
        .to("direct:processPDF");
        
        /**
         * Process source PDF
         */
        from("direct:processPDF")
        .description("Process input PDF")
        .log(DEBUG, "Processing PDF ${header.CamelFileAbsolutePath}")
        .setProperty("workingDir", simple("${header.CamelFileParent}"))
        .setProperty("pdfFile", simple("${header.CamelFileAbsolutePath}"))
        .removeHeaders("*")
        .setHeader("checkFile", simple("${property.workingDir}/OBJ.tiff"))
        .filter().expression(method(ExistenceCheck.class, "fileNotExists"))
            .to("direct:makeTiff")
        .end()
        .removeHeader("checkFile");

        /**
         * Make derivatives from Tiff
         */
        from("seda:processTiff?blockWhenFull=true&concurrentConsumers={{concurrent.consumers}}")
        .description("Make derivatives from Tiff")
        .log(DEBUG, "Process Tiff ${property.tiffFile}")
        .removeHeader("checkFile").setHeader("checkFile", simple("${property.workingDir}/JP2.jp2"))
        .filter().expression(method(ExistenceCheck.class, "fileNotExists"))
            .to("direct:makeJP2").end()
        .removeHeader("checkFile").setHeader("checkFile", simple("${property.workingDir}/JPG.jpg"))
        .filter().expression(method(ExistenceCheck.class, "fileNotExists"))
            .to("direct:makeJpeg").end()
        .removeHeader("checkFile").setHeader("checkFile", simple("${property.workingDir}/TN.jpg"))
        .filter().expression(method(ExistenceCheck.class, "fileNotExists"))
            .to("direct:makeTN").end()
        .removeHeader("checkFile").setHeader("checkFile", simple("${property.workingDir}/HOCR.html"))
        .filter().expression(method(ExistenceCheck.class, "fileNotExists"))
            .to("direct:makeHocr").end()
        .removeHeader("checkFile").setHeader("checkFile", simple("${property.workingDir}/OCR.txt"))
        .filter().expression(method(ExistenceCheck.class, "fileNotExists"))
            .to("direct:makeOcr").end()
        .removeHeader("checkFile");

        /**
         * Make Tiff from PDF
         */
        from("direct:makeTiff")
        .description("Make Tiff from PDF")
        .log(DEBUG, "Processing Tiff from ${property.pdfFile}")
        .log(TRACE, "Execute: exec:convert -density " + adjustedDensity + " ${property.pdfFile} +profile icc -resize 75% -colorspace rgb -alpha Off ${property.workingDir}/OBJ.tiff")
        .setBody(constant(null))
        .removeHeaders("*")
        .setHeader(EXEC_COMMAND_WORKING_DIR, simple("${property.workingDir}"))
        .setHeader(EXEC_COMMAND_ARGS, simple(" -density " + adjustedDensity + " ${property.pdfFile} +profile icc -resize 75% -colorspace rgb -alpha Off ${property.workingDir}/OBJ.tiff"))
        .to("exec:{{apps.convert}}")
        .log(DEBUG, "Finish exec with ${header." + EXEC_EXIT_VALUE + "}")
        .filter(header(EXEC_EXIT_VALUE).isEqualTo(0))
            .log(ERROR, "DID NOT generate Tiff from ${property.pdfFile}")
            .log(ERROR, "Error: ${header." + EXEC_STDERR + "}")
        .end();
        
        
        /**
         * Make JP2 from Tiff
         */
        from("direct:makeJP2")
        .description("Make a JP2 from Tiff")
        .log(DEBUG, "Create JP2 from ${property.tiffFile}")
        .to("direct:isCompressedTiff")
        .removeHeaders("*")
        .setHeader(EXEC_COMMAND_WORKING_DIR, simple("${property.workingDir}"))
        .choice()
        .when(body().isEqualTo(true))
            .to("direct:JP2FromCompressed")
        .otherwise()
            .to("direct:JP2FromUncompressed");
        
        /**
         * Make JP2 direct from Tiff
         */
        from("direct:JP2FromUncompressed")
        .description("Create a JP2 using ${property.tiffFile}")
        .log(DEBUG, "Create a JP2 using ${property.tiffFile} (uncompressed)")
        .removeHeaders("*")
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("-i ${property.tiffFile} -o ${property.workingDir}/JP2.jp2 Creversible=yes -rate -,1,0.5,0.25 Clevels=5"))
        .to("exec:{{apps.kdu_compress}}")
        .filter(header(EXEC_EXIT_VALUE).isEqualTo(1))
            .log(ERROR, "Problem creating JP2 with uncompressed Tiff.")
            .log(ERROR, "Error: ${header." + EXEC_STDERR + "}");
        
        /**
         * Uncompress Tiff and generate JP2
         */
        from("direct:JP2FromCompressed")
        .description("Create a JP2 using ${property.tiffFile} (compressed)")
        .log(DEBUG, "Creating JP2 from compressed Tiff")
        .removeHeaders("*")
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_WORKING_DIR, simple("${property.workingDir}"))
        .setHeader(EXEC_COMMAND_ARGS, simple(" -compress 'None' ${property.tiffFile} ${property.tiffFile}.tmp.tiff"))
        .to("exec:{{apps.convert}}")
        .removeHeaders("*")
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_WORKING_DIR, simple("${property.workingDir}"))
        .setHeader(EXEC_COMMAND_ARGS, simple("-i ${property.tiffFile}.tmp.tiff -o ${property.workingDir}/JP2.jp2 Creversible=yes -rate -,1,0.5,0.25 Clevels=5"))
        .to("exec:{{apps.kdu_compress}}")
        .filter(header(EXEC_EXIT_VALUE).isEqualTo(1))
            .log(ERROR, "Problem creating JP2 from compressed Tiff")
            .log(ERROR, "Error: ${header." + EXEC_STDERR + "}")
            .end()
        .setBody(constant(null))
        .removeHeaders("*")
        .setHeader(EXEC_COMMAND_WORKING_DIR, simple("${property.workingDir}"))
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile}.tmp.tiff"))
        .to("exec:/bin/rm");
        
        /**
         * Test if Tiff is compressed
         */
        from("direct:isCompressedTiff")
        .description("Test to see if Tiff is compressed")
        .log(DEBUG, "isCompressed for ${property.tiffFile}")
        .removeHeaders("*")
        .setHeader(EXEC_COMMAND_WORKING_DIR, simple("${property.workingDir}"))
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("-format '%[C]' ${property.tiffFile}"))
        .to("exec:{{apps.identify}}")
        .log(DEBUG, "isCompressed identify returns (${body})")
        .setBody(body().regexReplaceAll("('|\r|\n)", ""))
        .log(DEBUG, "isCompressed mutated to (${body})")
        .choice()
            .when(body().isEqualTo("None"))
                .log(DEBUG, "isCompress = false")
                .setBody(constant(false))
                .endChoice()
            .otherwise()
                .log(DEBUG, "isCompress = true")
                .setBody(constant(true))
        .end()
        .removeHeader(EXEC_EXIT_VALUE);

        /**
         * Make full sized Jpeg
         */
        from("direct:makeJpeg")
        .description("Make full sized JPeG from Tiff")
        .log(DEBUG, "Make JPEG for ${property.tiffFile} in ${property.workingDir}")
        .removeHeaders("*")
        .setHeader(EXEC_COMMAND_WORKING_DIR, simple("${property.workingDir}"))
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} ${property.workingDir}/JPG.jpg"))
        .to("exec:{{apps.convert}}")
        .filter(header(EXEC_EXIT_VALUE).isEqualTo(1))
            .log(ERROR, "Problem creating JPG - ${header." + EXEC_STDERR + "}");
        
        /**
         * Make thumbnail Jpeg
         */
        from("direct:makeTN")
        .description("Make thumbnail from Tiff")
        .log(DEBUG, "Make thumbnail for ${property.tiffFile} in ${property.workingDir}")
        .removeHeaders("*")
        .setHeader(EXEC_COMMAND_WORKING_DIR, simple("${property.workingDir}"))
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} -resize 110x110 ${property.workingDir}/TN.jpg"))
        .to("exec:{{apps.convert}}")
        .filter(header(EXEC_EXIT_VALUE).isEqualTo(1))
            .log(ERROR, "Problem creating TN - ${header." + EXEC_STDERR + "}");
        
        /**
         * Process HOCR
         */
        from("direct:makeHocr")
        .description("Make HOCR from Tiff")
        .log(DEBUG, "Make HOCR for ${property.tiffFile} in ${property.workingDir}")
        .removeHeaders("*")
        .setHeader(EXEC_COMMAND_WORKING_DIR, simple("${property.workingDir}"))
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} ${property.workingDir}/HOCR -l eng hocr"))
        .to("exec:{{apps.tesseract}}")
        .filter(header(EXEC_EXIT_VALUE).isEqualTo(1))
            .log(ERROR, "Problem creating HOCR - ${header." + EXEC_STDERR + "}")
            .to("direct:makeGreyTiff")
            .to("direct:OcrFromGray")
            .removeHeaders("*")
            .setHeader(EXEC_COMMAND_WORKING_DIR, simple("${property.workingDir}"))
            .setBody(constant(null))
            .setHeader(EXEC_COMMAND_ARGS, simple(" ${property.workingDir}/OBJ_gray.tiff"))
            .to("exec:/bin/rm")
            .end();
       
        /**
         * Process OCR
         */
        from("direct:makeOcr")
        .description("Make OCR from Tiff")
        .log(DEBUG, "Make OCR for ${property.tiffFile} in ${property.workingDir}")
        .removeHeaders("*")
        .setHeader(EXEC_COMMAND_WORKING_DIR, simple("${property.workingDir}"))
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} ${property.workingDir}/OCR -l eng"))
        .to("exec:{{apps.tesseract}}")
        .filter(header(EXEC_EXIT_VALUE).isEqualTo(1))
            .log(ERROR, "Problem creating OCR - ${header." + EXEC_STDERR + "}");

        /**
         * Make a Greyscale version of the Tiff
         */
        from("direct:makeGreyTiff")
        .description("Make a greyscale Tiff for Tesseract")
        .log(DEBUG, "Make Tiff greyscale")
        .removeHeaders("*")
        .setHeader(EXEC_COMMAND_WORKING_DIR, simple("${property.workingDir}"))
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} -colorspace gray -quality 100 ${property.workingDir}/OBJ_gray.tiff"))
        .to("exec:{{apps.convert}}")
        .filter(header(EXEC_EXIT_VALUE).isEqualTo(1))
            .log(ERROR, "Problem creating Greyscale Tiff - ${header." + EXEC_STDERR + "}");

        /**
         * If we failed to create HOCR/OCR from Tiff
         * use a greyscale copy of the Tiff. 
         */
         from("direct:OcrFromGray")
         .description("Make HOCR and OCR from grayscale copy of Tiff")
         .log(DEBUG, "Generate HOCR/OCR from ${property.workingDir}/OBJ_gray.tiff")
         .removeHeaders("*")
         .setHeader(EXEC_COMMAND_WORKING_DIR, simple("${property.workingDir}"))
         .setBody(constant(null))
         .setHeader(EXEC_COMMAND_ARGS, simple("${property.workingDir}/OBJ_gray.tiff ${property.workingDir}/HOCR -l eng hocr"))
         .to("exec:{{apps.tesseract}}")
         .choice()
         .when(header(EXEC_EXIT_VALUE).isEqualTo(0))
             .removeHeaders("*")
             .setHeader(EXEC_COMMAND_WORKING_DIR, simple("${property.workingDir}"))
             .setBody(constant(null))
             .setHeader(EXEC_COMMAND_ARGS, simple("${property.workingDir}/OBJ_gray.tiff ${property.workingDir}/OCR -l eng"))
             .to("exec:{{apps.tesseract}}").endChoice()
         .otherwise()
             .log(ERROR, "Problem generating HOCR from Grayscale tiff ${property.workingDir}/OBJ_gray.tiff")
         .end();
             
             
        
        /**
         * Make PDF
         */
        from("direct:makePDF")
        .description("Make PDF from Tiff")
        .log(DEBUG, "Make PDF for ${property.tiffFile}")
        .setProperty("pdfFile", simple("${property.workingDir}/PDF.pdf"))
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} ${property.pdfFile"))
        .to("exec:{{apps.convert}}");
        
    }

}
