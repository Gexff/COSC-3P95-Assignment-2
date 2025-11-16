/**
 * @Title COSC 3P95 Assignment 2
 *
 * @Author Geoffrey Jensen
 * @Student# 7148710
 *
 * @Author Justin Thomas Bijoy
 * @Student# 7123550
 *
 * This class handles a bulk transfer of files from a single client. It implements runnable so instances can run at the
 * same time.
 *
 * It is instantiated with a socket connection, and the transfer protocol is as follows:
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
 *      i.) The client sends the SPAN_ID for the file span
 *      ii.) The client fully compresses the file data, then encrypts the data
 *      iii.) The client sends the length of the compressed/encrypted data to the Server,
 *          then sends the compressed/encrypted data.
 *      iv.) The client sends the checksum of the unencrypted file to the server, by first sending the length of the
 *          checksum, followed by the checksum itself.
 *
 *      Server side:
 *      v.) The server reads the SPAN_ID for the file transfer
 *      vi.) The server reads the length of the compressed/encrypted data, then reads the compressed/encrypted data itself.
 *      vii.) The server decrypts the data, then decompresses it.
 *      viii.) The server reads the length of the checksum from the client, then reads the checksum itself.
 *      ix.) The server compares the checksum from the client to the checksum it calculates from the received data.
 */

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.Socket;
import java.security.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.zip.InflaterOutputStream;

import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

public class FolderTransferRequest implements Runnable {
    Socket client;
    ObjectInputStream oInputStream;
    ObjectOutputStream oOutputStream;
    DataInputStream dInputStream;
    Cipher cipher;

    private static final OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint("http://localhost:4317")
            .build();

    private static final OtlpGrpcMetricExporter metricExporter = OtlpGrpcMetricExporter.builder()
            .setEndpoint("http://localhost:4317")
            .build();

    static Resource app = Resource.getDefault().toBuilder().put("service.name", "COSC3P95-Part2").build();

    private static final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .setResource(app)
            .setSampler(Sampler.alwaysOn())
            .build();
    private static final SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .setResource(app)
            .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                    .setInterval(Duration.ofSeconds(2))
                    .build())
            .build();

    private static final OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .build();

    private static final Tracer tracer =
            openTelemetry.getTracer("file-transfer");

    private static final Meter meter = openTelemetry.meterBuilder("COSC3P95.Server").build();

    private static final LongCounter filesReceivedCounter = meter
            .counterBuilder("files_received_total")
            .setDescription("Total number of files successfully received")
            .build();
    private static final LongCounter checksumFailCounter = meter
            .counterBuilder("checksum_failures_total")
            .setDescription("Number of files where checksum verification failed")
            .build();

    static Span parentSpan;
    static Span fileSpan;

    public FolderTransferRequest(Socket client) throws IOException, NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException {
        oInputStream = new ObjectInputStream(client.getInputStream());
        oOutputStream = new ObjectOutputStream(client.getOutputStream());
        dInputStream = new DataInputStream(client.getInputStream());
        this.client = client;

        byte[] decodedKey = Base64.getDecoder().decode(Server.SECRET_KEY);
        SecretKey key = new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
        cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        IvParameterSpec ivParameterSpec = new IvParameterSpec(Server.IV);
        cipher.init(Cipher.DECRYPT_MODE, key, ivParameterSpec);
    }

    @Override
    public void run() {
        parentSpan = tracer.spanBuilder("server_file_transfer")
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        String folder = null;
        ArrayList<String> fileNames = null;

        // Send Span context to client
        try {
            oOutputStream.writeObject(parentSpan.getSpanContext().getTraceId());
            oOutputStream.writeObject(parentSpan.getSpanContext().getSpanId());
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Read folder name
        try {
            String[] tmp = ((String) oInputStream.readObject()).split("/"); // Don't accept paths as input
            folder = "Server_" + tmp[tmp.length-1];
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        // Read ArrayList of file names
        try {
            fileNames = (ArrayList<String>) oInputStream.readObject();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        if(folder == null || fileNames == null){
            System.out.println("Something went wrong");
            parentSpan.end();
            return;
        }

        File newFolder = new File(folder);
        if (newFolder.mkdir()) {
            System.out.println("Folder Created: " + folder);
        } else {
            System.out.println("Folder already exists: " + folder);
        }
        System.out.println();

        MessageDigest md = null;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            parentSpan.end();
            return;
        }

        for(String fileName : fileNames){
            try {
                // Read span ID
                String spanId = (String) oInputStream.readObject();

                fileSpan = Span.wrap(SpanContext.createFromRemoteParent(parentSpan.getSpanContext().getTraceId(),
                        spanId, TraceFlags.getSampled(), TraceState.getDefault()));

                // Read data length
                int length = dInputStream.readInt();

                byte[] retrievedData = readData(dInputStream, length);

                if(Server.USE_ADVANCED_FEATURES){
                    byte[] compressedData = decrypt(retrievedData, cipher);

                    byte[] rawData = decompress(compressedData);

                    writeToFile(rawData, folder + "/" + fileName, md);

                    byte[] clientDigest = readChecksum(dInputStream);

                    // Read MD5 hash
                    byte[] serverDigest = md.digest();

                    // Compare checksums
                    boolean match = compareChecksums(clientDigest, serverDigest);

                    if (match) {
                        System.out.println("MD5 Checksum matched for file: " + fileName);
                    } else {
                        System.out.println("MD5 Checksum DID NOT MATCH for file: " + fileName);
                    }
                }
                else{
                    writeToFile(retrievedData, folder + "/" + fileName, md);
                }

                oOutputStream.writeObject("sync");

                System.out.println("Finished transferring: " + fileName + "\n");

            } catch (IOException e) {
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }

        parentSpan.end();
        openTelemetry.getSdkTracerProvider().forceFlush();
    }

    private boolean compareChecksums(byte[] clientDigest, byte[] serverDigest) {
        Span span = tracer.spanBuilder("checksum_compare")
                .setParent(Context.current().with(fileSpan))
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        boolean result = false;
        try {
            result = MessageDigest.isEqual(clientDigest, serverDigest);
        } catch (Exception e) {
            span.recordException(e);
        } finally {
            span.setAttribute("pred.checksum_failed", !result);

            if (result) {
                filesReceivedCounter.add(1);
            } else {
                checksumFailCounter.add(1);
            }
            span.setAttribute("checksum.result", result);
            span.end();
        }
        return result;
    }

    private byte[] readChecksum(DataInputStream dInputStream) throws IOException {
        Span span = tracer.spanBuilder("read_checksum")
                .setParent(Context.current().with(fileSpan))
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        byte[] digest;
        try {
            int length = dInputStream.readInt();
            digest = new byte[length];
            dInputStream.read(digest, 0, length);
            span.setAttribute("checksum.length", length);
            span.addEvent("Checksum read from client");
        } finally {
            span.end();
        }
        return digest;
    }

    private void writeToFile(byte[] rawData, String file, MessageDigest md) throws IOException {
        Span span = tracer.spanBuilder("write_file")
                .setParent(Context.current().with(fileSpan))
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("File_size", rawData.length)
                .startSpan();
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(rawData);
        try(DigestInputStream digestInputStream = new DigestInputStream(byteArrayInputStream, md);
            FileOutputStream fileOutputStream = new FileOutputStream(file))
        {
            byte[] buffer = new byte[Server.BUFFER_SIZE];
            int bytes;
            span.addEvent("Writing data to " + file);
            while ((bytes = digestInputStream.read(buffer)) != -1) {
                fileOutputStream.write(buffer, 0, bytes);
            }
            span.addEvent("Write complete");
        } catch (IOException e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private byte[] decompress(byte[] data) {
        Span span = tracer.spanBuilder("decompress_data")
                .setParent(Context.current().with(fileSpan))
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try(InflaterOutputStream inflaterOutputStream = new InflaterOutputStream(byteArrayOutputStream))
        {
            inflaterOutputStream.write(data);
            span.addEvent("Decompression finished");
        } catch (IOException e) {
            e.printStackTrace();
            span.recordException(e);
        } finally {
            span.end();
        }

        return byteArrayOutputStream.toByteArray();
    }

    private byte[] decrypt(byte[] data, Cipher cipher) {
        Span span = tracer.spanBuilder("decrypt_data")
                .setParent(Context.current().with(fileSpan))
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try(CipherOutputStream cipherOutputStream = new CipherOutputStream(byteArrayOutputStream, cipher))
        {
            cipherOutputStream.write(data);
            span.addEvent("Decryption complete");
        } catch (IOException e) {
            e.printStackTrace();
            span.recordException(e);
        } finally {
            span.end();
        }

        return byteArrayOutputStream.toByteArray();
    }

    private byte[] readData(DataInputStream dInputStream, int length) throws IOException {
        Span span = tracer.spanBuilder("read_data")
                .setParent(Context.current().with(fileSpan))
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        byte[] retrievedData = new byte[length];

        // Read data
        int totalRead = 0;
        int read;
        try {
            while(totalRead < length && (read = dInputStream.read(retrievedData, totalRead, length - totalRead)) != -1) {
                totalRead += read;
            }
            span.setAttribute("bytes.read", totalRead);
        } catch (IOException e) {
            e.printStackTrace();
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
        return retrievedData;
    }
}