# Derivative-Route

This is simple WAR file that scans a directory and all sub-directories under it for PDFs or Tiffs. Made for use with [Islandora](http://islandora.ca)

If it finds a PDF, it generates a Tiff file as OBJ.tiff.

If it finds a OBJ.tiff, it generates JPG.jpg, TN.jpg, JP2.jp2, OCR.txt and HOCR.html files.

## Configuration

You can configure some options and behaviour via JAVA_OPTS.

| System Property | Default | Description |
| :---: | :---: | --- |
| derivatives.sourceDir | /tmp | The directory to watch |
| derivatives.sourceDensity | 300 | The expected density of the Tiff files |
| derivatives.concurrent | 1 | Number of concurrent consumers to use, careful as tesseract is single threaded. I keep this at CPUS - 1 |
| derivatives.apps.kdu_compress | /usr/bin/kdu_compress | Location of kdu_compress |
| derivatives.apps.convert | /usr/bin/convert | Location of Imagemagick convert |
| derivatives.apps.identify | /usr/bin/identify | Location of Imagemagick identify |
| derivatives.apps.tesseract | /usr/bin/tesseract | Location of tesseract |

These are defined in the application properties, but are currently not used.

| System Property | Default | Description |
| :---: | :---: | --- |
| derivatives.jmsBroker | tcp://localhost:61616 | JMS Queue broker |
| derivatives.jmsQueue | queue:derivative:input | JMS Queue name |

## Caveat Emptor

This is a god send and a demon. 

It does not work in any particular order, so figuring out when its done is a pain. 

But it works and makes generating derivatives for a book as easy as creating the page folders and dropping the OBJ.tiff in each one.

Eventually it would be better to deploy a queue and split this into a directory watcher that writes to the queue and a queue watcher that does the eventual work.

## Maintainers
[Jared Whiklo](https://github.com/whikloj)

## Licensing
MIT
