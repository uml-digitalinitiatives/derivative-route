package ca.umanitoba.dam;

import static org.slf4j.LoggerFactory.getLogger;
import static org.apache.camel.component.exec.ExecBinding.EXEC_COMMAND_ARGS;
import static org.apache.camel.component.exec.ExecBinding.EXEC_COMMAND_WORKING_DIR;
import static org.apache.camel.component.exec.ExecBinding.EXEC_EXIT_VALUE;
import static org.apache.camel.component.exec.ExecBinding.EXEC_COMMAND_OUT_FILE;
import static org.apache.camel.LoggingLevel.DEBUG;
import static org.apache.camel.LoggingLevel.INFO;

import org.apache.camel.PropertyInject;
import org.apache.camel.builder.RouteBuilder;

import org.slf4j.Logger;

public class DerivativeRoute extends RouteBuilder {

    private static final Logger logger = getLogger(DerivativeRoute.class);
    
    @PropertyInject(value = "source.density")
    private int density;
    
    @Override
    public void configure() throws Exception {
        
        final double adjustedDensity  = density * 1.25;
        
        /**
         * Listen for a PDF in this directory
         */
        from("file:{{source.directory}}?recursive=true&noop=true&include=.*\\.pdf")
        .description("Process PDF/Tiff files")
        .log("File is ${file:name.ext}")
        .to("direct:processPDF");
        
        /**
         * Listen for HOCR.hocr and move to HOCR.html
         */
        from("file:{{source.directory}}?recursive=true&include=HOCR.hocr&move=$simple{file:name.noext}.html")
        .description("HOCR.hocr to HOCR.html route")
        .log(DEBUG, "Move HOCR.hocr to ${header.CamelFileParent}/HOCR.html");
        //.recipientList(simple("file:${header.CamelFileParent}/HOCR.html"));
        
        /**
         * Listen for a file HOCR.txt in this directory
         */
        from("file:{{source.directory}}?recursive=true&delete=true&include=HOCR.txt")
        .description("HOCR.txt listener")
        .log(DEBUG, "Move HOCR.txt to ${header.CamelFileParent}/OCR.txt")
        .recipientList(simple("file:${header.CamelFileParent}/OCR.txt"));
        
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
        .to("direct:processTiff");

        /**
         * Make derivatives from Tiff
         */
        from("direct:processTiff")
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
        .log("Processing Tiff from ${property.pdfFile}")
        .setProperty("tiffFile", simple("${property.workingDir}/OBJ.tiff"))
        .log("Execute: exec:convert -density " + adjustedDensity + " ${property.pdfFile} -resize 75% -colorspace rgb -alpha Off ${property.tiffFile}")
        .recipientList(simple("exec:convert?args= -density " + adjustedDensity + " ${property.pdfFile} -resize 75% -colorspace rgb -alpha Off ${property.tiffFile}"))
        .log("Finish exec with ${header." + EXEC_EXIT_VALUE + "}")
        .choice()
        .when(header(EXEC_EXIT_VALUE).isEqualTo(0))
        .log("Generated Tiff from ${property.pdfFile}")
        .otherwise()
        .log("DID NOT generate Tiff from ${property.pdfFile}")
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
              .log(INFO, "Tiff is compressed, uncompressing")
              .setHeader(EXEC_COMMAND_ARGS, simple(" -compress 'None' ${property.tiffFile} ${property.tiffFile}.tmp.tiff"))
              .to("exec:convert?useStderrOnEmptyStdout=false")
              .setProperty("tempTiffName", simple("${property.tiffFile}.tmp.tiff"))
          .otherwise()
              .log(DEBUG, "body is false, using ${property.tiffFile}")
              .setProperty("tempTiffName", exchangeProperty("tiffFile"))
        .end()
        .setHeader(EXEC_COMMAND_ARGS, simple("-i ${property.tempTiffName} -o ${property.workingDir}/JP2.jp2 Creversible=yes -rate -,1,0.5,0.25 Clevels=5"))
        .to("exec:kdu_compress?useStderrOnEmptyStdout=false")
        .log(DEBUG, "Result from JP2 creation is (${header." + EXEC_EXIT_VALUE + "})");
              
        /**
         * Test if Tiff is compressed
         */
        from("direct:isCompressedTiff")
        .description("Test to see if Tiff is compressed")
        .log(DEBUG, "isCompressed for ${property.tiffFile}")
        .setHeader(EXEC_COMMAND_ARGS, simple("-format '%[C]' ${property.tiffFile}"))
        .to("exec:identify?useStderrOnEmptyStdout=false")
        .log(DEBUG, "isCompressed identify returns (${body})")
        .setBody(body().regexReplaceAll("'", ""))
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
        .log("Make JPEG for ${property.tiffFile}")
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} ${property.workingDir}/JPG.jpg"))
        .to("exec:convert?useStderrOnEmptyStdout=false");
        
        /**
         * Make thumbnail Jpeg
         */
        from("direct:makeTN")
        .description("Make thumbnail from Tiff")
        .log(DEBUG, "Make thumbnail for ${property.tiffFile}")
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} -resize 110x110 ${property.workingDir}/TN.jpg"))
        .to("exec:convert?useStderrOnEmptyStdout=false");
        
        /**
         * Process HOCR
         */
        from("direct:makeHocr")
        .description("Make HOCR from Tiff")
        .log(DEBUG, "Make HOCR for ${property.tiffFile}")
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} ${property.workingDir}/HOCR -l eng hocr"))
        .to("exec:tesseract?useStderrOnEmptyStdout=false");
        
        /**
         * Process OCR
         */
        from("direct:makeOcr")
        .description("Make OCR from Tiff")
        .log(DEBUG, "Make OCR for ${property.tiffFile}")
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} ${property.workingDir}/OCR -l eng"))
        .to("exec:tesseract?useStderrOnEmptyStdout=false");
        
        /**
         * Make PDF
         */
        from("direct:makePDF")
        .description("Make PDF from Tiff")
        .log(DEBUG, "Make PDF for ${property.tiffFile}")
        .setProperty("pdfFile", simple("${property.workingDir}/PDF.pdf"))
        .setHeader(EXEC_COMMAND_ARGS, simple("${property.tiffFile} ${property.pdfFile"))
        .to("exec:convert?useStderrOnEmptyStdout=false");
        
    }

}
