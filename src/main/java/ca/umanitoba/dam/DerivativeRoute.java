package ca.umanitoba.dam;

import static org.slf4j.LoggerFactory.getLogger;
import static org.apache.camel.component.exec.ExecBinding.EXEC_COMMAND_ARGS;
import static org.apache.camel.component.exec.ExecBinding.EXEC_COMMAND_WORKING_DIR;
import static org.apache.camel.component.exec.ExecBinding.EXEC_EXIT_VALUE;
import static org.apache.camel.component.exec.ExecBinding.EXEC_STDERR;
import static org.apache.camel.LoggingLevel.DEBUG;
import static org.apache.camel.LoggingLevel.INFO;
import static org.apache.camel.LoggingLevel.ERROR;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.PropertyInject;
import org.apache.camel.builder.RouteBuilder;

import org.slf4j.Logger;

public class DerivativeRoute extends RouteBuilder {

    private static final Logger logger = getLogger(DerivativeRoute.class);
    
    @PropertyInject(value = "source.density")
    private int density;
    
    @PropertyInject(value = "source.directory")
    private String directory;
    
    @Override
    public void configure() throws Exception {
        
        final double adjustedDensity  = density * 1.25;
        
        logger.debug("source.directory is {}", this.directory);
        
        /**
         * Listen for a PDF in this directory
         */
        from("file:{{source.directory}}?recursive=true&noop=true&include=.*\\.pdf")
        .description("Process PDF/Tiff files")
        .log("File is ${file:name}")
        .to("direct:processPDF");
        
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
         * Process source PDF
         */
        from("direct:processPDF")
        .description("Process input PDF")
        .log("Starting Processing for ${header.CamelFileNameOnly}")
        .setProperty("workingDir", simple("${header.CamelFileParent}"))
        .setProperty("pdfFile", simple("${header.CamelFileAbsolutePath}"))
        .removeHeaders("*")
        .setHeader(EXEC_COMMAND_WORKING_DIR, simple("${property.workingDir}"))
        .to("direct:makeTiff")
        .to("seda:processTiff");

        /**
         * Make derivatives from Tiff
         */
        from("seda:processTiff?blockWhenFull=true&concurrentConsumers={{concurrent.consumers}}")
        .description("Make derivatives from Tiff")
        .to("direct:makeJP2")
        .to("direct:makeJpeg")
        .to("direct:makeTN")
        .to("direct:makeHocr")
        .to("direct:makeOcr");
        
        
        /**
         * Make Tiff from PDF
         */
        from("direct:makeTiff")
        .description("Make Tiff from PDF")
        .log(DEBUG, "Processing Tiff from ${property.pdfFile}")
        .log(DEBUG, "Execute: exec:convert -density " + adjustedDensity + " ${property.pdfFile} -resize 75% -alpha Off ${property.workingDir}/OBJ.tiff")
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple(" -density " + adjustedDensity + " ${property.pdfFile} -resize 75% -alpha Off ${property.workingDir}/OBJ.tiff"))
        .to("exec:convert")
        //.recipientList(simple("exec:convert?args= -density " + adjustedDensity + " ${property.pdfFile} -resize 75% -alpha Off ${property.workingDir}/OBJ.tiff"))
        .log(DEBUG, "Finish exec with ${header." + EXEC_EXIT_VALUE + "}")
        .choice()
        .when(header(EXEC_EXIT_VALUE).isEqualTo(0))
            .log("Generated Tiff from ${property.pdfFile}")
        .otherwise()
            .log(ERROR, "DID NOT generate Tiff from ${property.pdfFile}")
            .log(ERROR, "Error: ${header." + EXEC_STDERR + "}")
        .end();
        
        
        /**
         * Make JP2 from Tiff
         */
        from("direct:makeJP2")
        .description("Make a JP2 from Tiff")
        .log("Create JP2 from ${property.tiffFile}")
        .to("direct:isCompressedTiff")
        .log("body is (${body})")
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
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("-i ${property.tiffFile} -o ${property.workingDir}/JP2.jp2 Creversible=yes -rate -,1,0.5,0.25 Clevels=5"))
        .to("exec:kdu_compress?useStderrOnEmptyStdout=true")
        .filter(header(EXEC_EXIT_VALUE).isEqualTo(1))
            .log(ERROR, "Problem creating JP2 with uncompressed Tiff.")
            .to("log:?logger=myLogger&level=ERROR");
        
        /**
         * Uncompress Tiff and generate JP2
         */
        from("direct:JP2FromCompressed")
        .description("Create a JP2 using ${property.tiffFile} (compressed)")
        .log(DEBUG, "Creating JP2 from compressed Tiff")
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple(" -compress 'None' ${property.tiffFile} ${property.tiffFile}.tmp.tiff"))
        .to("exec:convert?useStderrOnEmptyStdout=false")
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("-i ${property.tiffFile}.tmp.tiff -o ${property.workingDir}/JP2.jp2 Creversible=yes -rate -,1,0.5,0.25 Clevels=5"))
        .to("exec:kdu_compress?useStderrOnEmptyStdout=true")
        .choice()
        .when(header(EXEC_EXIT_VALUE).isEqualTo(1))
            .log(ERROR, "Problem creating JP2 from compressed Tiff")
            .to("log:?logger=myLogger&level=DEBUG")
            .end()
        .setBody(constant(null))
        .to("exec:rm ${property.tiffFile}.tmp.tiff");
        
        /**
         * Test if Tiff is compressed
         */
        from("direct:isCompressedTiff")
        .description("Test to see if Tiff is compressed")
        .log(DEBUG, "isCompressed for ${property.tiffFile}")
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("-format '%[C]' ${property.tiffFile}"))
        .to("exec:identify?useStderrOnEmptyStdout=false")
        .log(DEBUG, "isCompressed identify returns (${body})")
        .setBody(body().regexReplaceAll("('|\r|\n)", ""))
        .log(DEBUG, "isCompressed mutated to (${body})")
        .choice()
            .when(body().isEqualTo("None"))
                .log(DEBUG, "isCompress = false")
                .setBody(constant(false))
            .otherwise()
                .log(DEBUG, "isCompress = true")
                .setBody(constant(true))
        .end();

        /**
         * Make full sized Jpeg
         */
        from("direct:makeJpeg")
        .description("Make full sized JPeG from Tiff")
        .log("Make JPEG for ${property.tiffFile} in ${property.workingDir}")
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} ${property.workingDir}/JPG.jpg"))
        .to("exec:convert?useStderrOnEmptyStdout=true")
        .filter(header(EXEC_EXIT_VALUE).isEqualTo(1))
            .log(ERROR, "Problem creating JPG")
            .to("log:?logger=myLogger&level=ERROR");
        
        /**
         * Make thumbnail Jpeg
         */
        from("direct:makeTN")
        .description("Make thumbnail from Tiff")
        .log(DEBUG, "Make thumbnail for ${property.tiffFile} in ${property.workingDir}")
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} -resize 110x110 ${property.workingDir}/TN.jpg"))
        .to("exec:convert?useStderrOnEmptyStdout=true")
        .filter(header(EXEC_EXIT_VALUE).isEqualTo(1))
            .log(ERROR, "Problem creating TN")
            .to("log:?logger=myLogger&level=ERROR");
        
        /**
         * Process HOCR
         */
        from("direct:makeHocr")
        .description("Make HOCR from Tiff")
        .log(DEBUG, "Make HOCR for ${property.tiffFile} in ${property.workingDir}")
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} ${property.workingDir}/HOCR -l eng hocr"))
        .to("exec:tesseract?useStderrOnEmptyStdout=true")
        .filter(header(EXEC_EXIT_VALUE).isEqualTo(1))
            .log(ERROR, "Problem creating HOCR")
            .to("log:?logger=myLogger&level=ERROR");
        
        /**
         * Process OCR
         */
        from("direct:makeOcr")
        .description("Make OCR from Tiff")
        .log(DEBUG, "Make OCR for ${property.tiffFile} in ${property.workingDir}")
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} ${property.workingDir}/OCR -l eng"))
        .to("exec:tesseract?useStderrOnEmptyStdout=true")
        .filter(header(EXEC_EXIT_VALUE).isEqualTo(1))
        .log(ERROR, "Problem creating OCR")
        .to("log:?logger=myLogger&level=ERROR");
        
        /**
         * Make PDF
         */
        from("direct:makePDF")
        .description("Make PDF from Tiff")
        .log(DEBUG, "Make PDF for ${property.tiffFile}")
        .setProperty("pdfFile", simple("${property.workingDir}/PDF.pdf"))
        .setBody(constant(null))
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} ${property.pdfFile"))
        .to("exec:convert?useStderrOnEmptyStdout=false");
        
    }

}
