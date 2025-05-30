package stroom.proxy.app.handler;

import stroom.meta.api.AttributeMap;
import stroom.meta.api.AttributeMapUtil;
import stroom.meta.api.StandardHeaderArguments;
import stroom.proxy.StroomStatusCode;
import stroom.receive.common.DataReceiptMetrics;
import stroom.receive.common.ReceiptIdGenerator;
import stroom.receive.common.RequestAuthenticator;
import stroom.receive.common.RequestHandler;
import stroom.receive.common.StroomStreamException;
import stroom.util.cert.CertificateExtractor;
import stroom.util.concurrent.UniqueId;
import stroom.util.date.DateUtil;
import stroom.util.io.StreamUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.net.HostNameUtil;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.hc.core5.http.HttpStatus;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;

/**
 * Main entry point to handling proxy requests.
 * <p>
 * This class used the main context and forwards the request on to our
 * dynamic mini proxy.
 */
public class ProxyRequestHandler implements RequestHandler {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ProxyRequestHandler.class);
    private static final String ZERO_CONTENT = "0";

    private final RequestAuthenticator requestAuthenticator;
    private final CertificateExtractor certificateExtractor;
    private final ReceiverFactory receiverFactory;
    private final ReceiptIdGenerator receiptIdGenerator;
    private final DataReceiptMetrics dataReceiptMetrics;
    private final String hostName;

    @Inject
    public ProxyRequestHandler(final RequestAuthenticator requestAuthenticator,
                               final CertificateExtractor certificateExtractor,
                               final ReceiverFactory receiverFactory,
                               final ReceiptIdGenerator receiptIdGenerator,
                               final DataReceiptMetrics dataReceiptMetrics) {
        this.requestAuthenticator = requestAuthenticator;
        this.certificateExtractor = certificateExtractor;
        this.receiverFactory = receiverFactory;
        this.receiptIdGenerator = receiptIdGenerator;
        this.dataReceiptMetrics = dataReceiptMetrics;
        this.hostName = HostNameUtil.determineHostName();
    }

    @Override
    public void handle(final HttpServletRequest request, final HttpServletResponse response) {
        dataReceiptMetrics.timeRequest(() -> {
            doHandle(request, response);
        });
    }

    private void doHandle(final HttpServletRequest request, final HttpServletResponse response) {
        try {
            final Instant startTime = Instant.now();

            // Create attribute map from headers.
            final AttributeMap attributeMap = AttributeMapUtil.create(request, certificateExtractor);

            // Create a new proxy id for the request, so we can track progress of the stream
            // through the various proxies and into stroom and report back the ID to the sender,
            final UniqueId receiptId = receiptIdGenerator.generateId();

            // Authorise request.
            requestAuthenticator.authenticate(request, attributeMap);

            final String receiptIdStr = receiptId.toString();
            LOGGER.debug("Adding meta attribute {}: {}", StandardHeaderArguments.RECEIPT_ID, receiptIdStr);
            attributeMap.put(StandardHeaderArguments.RECEIPT_ID, receiptIdStr);
            attributeMap.appendItem(StandardHeaderArguments.RECEIPT_ID_PATH, receiptIdStr);

            // Save the time the data was received.
            attributeMap.computeIfAbsent(StandardHeaderArguments.RECEIVED_TIME, k ->
                    DateUtil.createNormalDateTimeString());

            // Append the hostname.
            appendReceivedPath(attributeMap);

            // Treat differently depending on compression type.
            String compression = attributeMap.get(StandardHeaderArguments.COMPRESSION);
            if (compression != null && !compression.isEmpty()) {
                compression = compression.toUpperCase(StreamUtil.DEFAULT_LOCALE);
                if (!StandardHeaderArguments.VALID_COMPRESSION_SET.contains(compression)) {
                    throw new StroomStreamException(
                            StroomStatusCode.UNKNOWN_COMPRESSION, attributeMap, compression);
                }
            }

            final String contentLength = attributeMap.get(StandardHeaderArguments.CONTENT_LENGTH);
            dataReceiptMetrics.recordContentLength(contentLength);

            if (ZERO_CONTENT.equals(contentLength)) {
                LOGGER.warn("process() - Skipping Zero Content " + attributeMap);
            } else {
                final Receiver receiver = receiverFactory.get(attributeMap);
                receiver.receive(
                        startTime,
                        attributeMap,
                        request.getRequestURI(),
                        request::getInputStream);
            }

            response.setStatus(HttpStatus.SC_OK);

            LOGGER.debug(() -> "Writing proxy receipt id attribute to response: " + receiptIdStr);
            try (final PrintWriter writer = response.getWriter()) {
                writer.println(receiptIdStr);
            } catch (final IOException e) {
                LOGGER.error(e.getMessage(), e);
            }
        } catch (final StroomStreamException e) {
            e.sendErrorResponse(response);
        }
    }

    private void appendReceivedPath(final AttributeMap attributeMap) {
//        if (appendReceivedPath) {
        // Here we build up a list of stroom servers that have received
        // the message

        // The initial one will be initially set at the boundary proxy/stroom server
        final String entryReceivedServer = attributeMap.get(StandardHeaderArguments.RECEIVED_PATH);

        if (entryReceivedServer != null) {
            if (!entryReceivedServer.contains(hostName)) {
                attributeMap.put(StandardHeaderArguments.RECEIVED_PATH,
                        entryReceivedServer + "," + hostName);
            }
        } else {
            attributeMap.put(StandardHeaderArguments.RECEIVED_PATH, hostName);
        }
//        }
    }
}
