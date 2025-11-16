/**
 * @Title COSC 3P95 Assignment 2
 *
 * @Author Geoffrey Jensen
 * @Student# 7148710
 *
 * @Author Justin Thomas Bijoy
 * @Student# 7123550
 *
 * This is the Client class that connects to the server and sends its files. This program accepts the name of the
 * folder to be transferred via command line parameter.
 *
 * The transfer protocol is as follows:
 *
 *  1.) Server sends the TRACE_ID and SPAN_ID to the client.
 *
 *  2.) Client sends the name of the folder to store the transferred files in to the server as a String object.
 *
 *  3.) Client sends an ArrayList object containing the names of the files that will be transferred to the Server.
 *  The server assumes the file transfer order will be the order of names in the ArrayList.
 *
 *  4.) For each file name in the ArrayList:
 *      Client side:
 *      i.) The client fully compresses the file data, then encrypts the data
 *      ii.) The client sends the length of the compressed/encrypted data to the Server,
 *          then sends the compressed/encrypted data.
 *      iii.) The client sends the checksum of the unencrypted file to the server, by first sending the length of the
 *          checksum, followed by the checksum itself.
 *
 *      Server side:
 *      iv.) The server reads the length of the compressed/encrypted data, then reads the compressed/encrypted data itself.
 *      v.) The server decrypts the data, then decompresses it.
 *      vi.) The server reads the length of the checksum from the client, then reads the checksum itself.
 *      vii.) The server compares the checksum from the client to the checksum it calculates from the received data.
 */


import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;

import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.samplers.Sampler;

public class Client {
    ObjectInputStream oInputStream;
    ObjectOutputStream oOutputStream;
    DataOutputStream dOutputStream;
    Cipher cipher;
    ByteArrayOutputStream byteArrayOutputStream;
    PredicateLogger logger = new PredicateLogger("run_" + System.currentTimeMillis() + ".txt");

    private static final OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint("http://localhost:4317")
            .build();

    private static final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .setResource(Resource.getDefault().toBuilder().put("service.name", "COSC3P95-Part2").build())
            .setSampler(Sampler.alwaysOn())
            .build();

    private static final OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build();

    private static final Tracer tracer =
            openTelemetry.getTracer("file-transfer");

    private static final Meter meter =
            openTelemetry.getMeter("file-transfer-metrics");

    private static final LongCounter filesTransferred =
            meter.counterBuilder("files_transferred_total")
                    .setUnit("files")
                    .setDescription("Number of files successfully sent")
                    .build();

    private static final DoubleHistogram compressionRatioHistogram =
            meter.histogramBuilder("compression_ratio")
                    .setDescription("Ratio before/after compression")
                    .setUnit("ratio")
                    .build();

    static Span parentSpan;
    static Span fileSpan;

    public Client(String host, int portNumber, String folderPath) throws Exception {
        File folder = new File(folderPath);
        File[] files = folder.listFiles();

        if(files == null){
            throw new Exception("No files found in: " + folderPath);
        }

        String[] tmp = folderPath.split("/");
        String folderName = tmp[tmp.length-1];

        try(Socket socket = new Socket(host, portNumber)) {
            oOutputStream = new ObjectOutputStream(socket.getOutputStream());
            oInputStream = new ObjectInputStream(socket.getInputStream());
            dOutputStream = new DataOutputStream(socket.getOutputStream());

            byteArrayOutputStream = new ByteArrayOutputStream(Server.BUFFER_SIZE);

            byte[] decodedKey = Base64.getDecoder().decode(Server.SECRET_KEY);
            SecretKey key = new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
            cipher = Cipher.getInstance("AES/CFB8/NoPadding");
            IvParameterSpec ivParameterSpec = new IvParameterSpec(Server.IV);
            cipher.init(Cipher.ENCRYPT_MODE, key, ivParameterSpec);

            // Receive TRACE_ID and SPAN_ID
            String traceId = (String) oInputStream.readObject();
            String spanId = (String) oInputStream.readObject();

            parentSpan = Span.wrap(SpanContext.createFromRemoteParent(traceId,
                    spanId, TraceFlags.getSampled(), TraceState.getDefault()));

            // Send folder name
            oOutputStream.writeObject(folderName);
            parentSpan.addEvent("Sent folder name");

            // Create ArrayList of file names
            ArrayList<String> fileNames = new ArrayList<>();
            for (File file : files){
                fileNames.add(file.getName());
            }
            parentSpan.addEvent("Sent ArrayList of file names");

            // Send ArrayList of file names
            oOutputStream.writeObject(fileNames);

            MessageDigest md = MessageDigest.getInstance("MD5");

            int number = 1;
            for(File file : files){
                fileSpan = tracer.spanBuilder("single_file_transfer")
                        .setParent(Context.current().with(parentSpan))
                        .setSpanKind(SpanKind.CLIENT)
                        .setAttribute("File.name", file.getName())
                        .setAttribute("File.size", file.length())
                        .setAttribute("File.number", number++)
                        .startSpan();

                logger.log("----------------------------------");
                boolean isFileNumberlt10 = number < 10; // < 10
                fileSpan.setAttribute("pred.file_no_lt_10", isFileNumberlt10);
                logger.log("pred.file_no_lt_10=" + isFileNumberlt10);

                boolean isFileNumbergt10 = number > 10; // > 10
                fileSpan.setAttribute("pred.file_no_gt_10", isFileNumbergt10);
                logger.log("pred.file_no_gt_10=" + isFileNumbergt10);

                oOutputStream.writeObject(fileSpan.getSpanContext().getSpanId());

                byte[] data = readFile(file);

                if(Server.USE_ADVANCED_FEATURES){
                    fileSpan.setAttribute("pred.USE_ADVANCED_FEATURES", Server.USE_ADVANCED_FEATURES);
                    logger.log("pred.USE_ADVANCED_FEATURES=" + Server.USE_ADVANCED_FEATURES);

                    byte[] compressedData = compress(data, md);
                    byte[] encryptedCompressedData = encrypt(compressedData, cipher);
                    sendData(dOutputStream, encryptedCompressedData);
                    sendChecksum(dOutputStream, md);
                }
                else{
                    sendData(dOutputStream, data);
                }

                String sync = (String) oInputStream.readObject();

                System.out.println("Finished transferring: " + file.getName() + ", Size: " + file.length());

                fileSpan.end();
            }

            oOutputStream.close();
            dOutputStream.close();
        } catch (UnknownHostException e) {
            logger.markException();
            e.printStackTrace();
        } catch (IOException e) {
            logger.markException();
            e.printStackTrace();
        } finally {
            logger.finalizeLog();
            openTelemetry.getSdkTracerProvider().shutdown();
        }
    }

    private byte[] readFile(File file) {
        Span span = tracer.spanBuilder("read_file")
                .setParent(Context.current().with(fileSpan))
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        // Read file into memory
        byte[] data = null;
        try {
            data = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            logger.markException();
            e.printStackTrace();
        } finally {
            span.end();
        }
        return data;
    }

    private void sendChecksum(DataOutputStream dOutputStream, MessageDigest md) throws IOException {
        Span span = tracer.spanBuilder("send_checksum")
                .setParent(Context.current().with(fileSpan))
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        span.addEvent("checksum.start");

        // Send length of digest and contents
        byte[] digest = md.digest();

        boolean Client_compareMD5 = md.getAlgorithm().equals("MD5");
        boolean Client_compareSHA256 = md.getAlgorithm().equals("SHA_256");
        span.setAttribute("pred.algorithm_MD5", Client_compareMD5);
        logger.log("pred.algorithm_MD5=" + Client_compareMD5);
        span.setAttribute("pred.algorithm_SHA_256", Client_compareSHA256);
        logger.log("pred.algorithm_SHA_256=" + Client_compareSHA256);
        dOutputStream.writeInt(digest.length);
        dOutputStream.write(digest, 0, digest.length);
        dOutputStream.flush();

        span.setAttribute("checksum.length", digest.length);
        span.addEvent("checksum.end");
        span.end();
    }

    private void sendData(DataOutputStream dOutputStream, byte[] data) throws IOException {
        Span span = tracer.spanBuilder("send_data")
                .setParent(Context.current().with(fileSpan))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("data.size.bytes", data.length)
                .startSpan();
        span.addEvent("sending.start");

        boolean isLargeFilelt5MB = data.length < 5L * 1024L * 1024L; // < 5MB
        span.setAttribute("pred.file_size_lt_5MB", isLargeFilelt5MB);
        logger.log("pred.file_size_lt_5MB=" + isLargeFilelt5MB);

        boolean isLargeFilegt5MB = data.length > 5L * 1024L * 1024L; // > 5MB
        span.setAttribute("pred.file_size_gt_5MB", isLargeFilegt5MB);
        logger.log("pred.file_size_gt_5MB=" + isLargeFilegt5MB);

        boolean isLargeFilelt10MB = data.length < 10L * 1024L * 1024L; // < 10MB
        span.setAttribute("pred.file_size_lt_10MB", isLargeFilelt10MB);
        logger.log("pred.file_size_lt_10MB=" + isLargeFilelt10MB);

        boolean isLargeFilegt10MB = data.length > 10L * 1024L * 1024L; // > 10MB
        span.setAttribute("pred.file_size_gt_10MB", isLargeFilegt10MB);
        logger.log("pred.file_size_gt_10MB=" + isLargeFilegt10MB);

        // Send data length and contents
        dOutputStream.writeInt(data.length);
        dOutputStream.write(data);

        span.addEvent("sending.end");
        span.end();
    }

    private byte[] encrypt(byte[] data, Cipher cipher) {
        Span span = tracer.spanBuilder("encrypt_file")
                .setParent(Context.current().with(fileSpan))
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        span.addEvent("encryption.start");

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try(CipherOutputStream cipherOutputStream = new CipherOutputStream(byteArrayOutputStream, cipher))
        {
            cipherOutputStream.write(data);
        } catch (IOException e) {
            e.printStackTrace();
            span.recordException(e);
            logger.markException();
        }

        span.addEvent("encryption.end");
        span.end();

        return byteArrayOutputStream.toByteArray();
    }

    private byte[] compress(byte[] data, MessageDigest md) {
        Span span = tracer.spanBuilder("compress_file")
                .setParent(Context.current().with(fileSpan))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("original_size.bytes", data.length)
                .startSpan();
        span.addEvent("compression.start");

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try(DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(byteArrayOutputStream);
            DigestOutputStream digestOutputStream = new DigestOutputStream(deflaterOutputStream, md))
        {
            digestOutputStream.write(data);
            deflaterOutputStream.finish();
        } catch (IOException e) {
            e.printStackTrace();
            span.recordException(e);
            logger.markException();
        }

        byte[] compressed = byteArrayOutputStream.toByteArray();
        double ratio = (double)data.length / (double)compressed.length;

        span.setAttribute("compressed_size.bytes", compressed.length);

        span.setAttribute("compression.ratio", ratio);
        span.setAttribute("pred.compression_ratio_gt_2_08",  ratio > 2.08);
        logger.log("pred.compression_ratio_gt_2_08=" + (ratio > 2.08));
        span.setAttribute("pred.compression_ratio_gt_2_085", ratio > 2.085);
        logger.log("pred.compression_ratio_gt_2_085=" + (ratio > 2.085));
        span.setAttribute("pred.compression_ratio_gt_2_087", ratio > 2.087);
        logger.log("pred.compression_ratio_gt_2_087=" + (ratio > 2.087));
        span.setAttribute("pred.compression_ratio_gt_2_0875", ratio > 2.0875);
       logger.log("pred.compression_ratio_gt_2_0875=" + (ratio > 2.0875));
        span.setAttribute("pred.compression_ratio_gt_2_08751", ratio > 2.08751);
        logger.log("pred.compression_ratio_gt_2_08751=" + (ratio > 2.08751));
        span.setAttribute("pred.compression_ratio_gt_2_08752", ratio > 2.08752);
        logger.log("pred.compression_ratio_gt_2_08752=" + (ratio > 2.08752));
        span.setAttribute("pred.compression_ratio_gt_2_08753", ratio > 2.087523);
        logger.log("pred.compression_ratio_gt_2_08753=" + (ratio > 2.08753));
        span.setAttribute("pred.compression_ratio_gt_2_08754", ratio > 2.08754);
        logger.log("pred.compression_ratio_gt_2_08754=" + (ratio > 2.08754));

        // Bug
        boolean corrupted = false;
        if (ratio > 2.087535 && compressed.length > 0) {
            compressed[0] ^= 0x01;  // flip lowest bit of first byte
            corrupted = true;
            logger.markException();
        }
        span.setAttribute("bug.corrupted", corrupted);
        logger.log("bug.corrupted=" + corrupted);

        compressionRatioHistogram.record(ratio);

        span.setAttribute("compressed_size.bytes", compressed.length);
        span.addEvent("compression.end");
        span.end();

        return compressed;
    }

    public static void main(String[] args) {
        if (args.length < 1){
            System.out.println("Please enter the name of the folder to transfer!");
            System.exit(1);
        }
        try {
            new Client("localhost", 5000, args[0]);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}