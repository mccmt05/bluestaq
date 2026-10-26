package com.bluestaq.notes.config;

import com.bluestaq.notes.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Bounds authenticated request bodies before MVC or Jackson can parse them.
 * At most the configured byte limit plus one byte is read, including for chunked or unknown-length bodies.
 * Accepted bytes are replayed to the synchronous MVC API so the original stream is not consumed twice.
 */
public final class RequestBodyLimitFilter extends OncePerRequestFilter {
    private final int maxBytes;
    private final ObjectMapper mapper;

    /**
     * Creates a body guard with the validated application byte budget.
     *
     * @param maxBytes maximum accepted bytes before deserialization
     * @param mapper serializer for client-safe errors
     */
    public RequestBodyLimitFilter(int maxBytes, ObjectMapper mapper) {
        this.maxBytes = maxBytes;
        this.mapper = mapper;
    }

    /**
     * Rejects oversized declared or actual bodies with 413 and passes a bounded replayable body downstream.
     *
     * @param request current HTTP request
     * @param response HTTP response used for an oversized-body error
     * @param chain remaining security filters and MVC handler
     * @throws IOException if request reading or response writing fails
     * @throws ServletException if downstream servlet processing fails
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws IOException, ServletException {
        byte[] body = request.getContentLengthLong() > maxBytes
                ? null : request.getInputStream().readNBytes(maxBytes + 1);
        if (body == null || body.length > maxBytes) {
            response.setStatus(413);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(),
                    new ApiError(413, "PAYLOAD_TOO_LARGE", "Request body exceeds the allowed size"));
            return;
        }
        chain.doFilter(new BoundedBodyRequest(request, body), response);
    }

    /**
     * Supplies the already bounded bytes through both Servlet request-reading APIs.
     */
    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {
        private final BodyInputStream input;
        private final int length;

        /**
         * Retains request metadata while replacing only the input stream and body length.
         *
         * @param request original authenticated request
         * @param body bytes admitted by the size check
         */
        private BoundedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            input = new BodyInputStream(body);
            length = body.length;
        }

        /**
         * Returns the accepted body as a synchronous Servlet stream.
         *
         * @return the stream shared by request consumers
         */
        @Override
        public ServletInputStream getInputStream() {
            return input;
        }

        /**
         * Decodes the bounded stream for consumers using a character reader.
         *
         * @return a reader honoring the declared character encoding, or UTF-8 for JSON's default
         */
        @Override
        public BufferedReader getReader() {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(input, charset));
        }

        /**
         * Reports the actual accepted body length, including when the original transfer was chunked.
         *
         * @return number of buffered bytes
         */
        @Override
        public int getContentLength() {
            return length;
        }

        /**
         * Reports the buffered body length through the long-valued Servlet API.
         *
         * @return number of buffered bytes
         */
        @Override
        public long getContentLengthLong() {
            return length;
        }
    }

    /**
     * Adapts a bounded in-memory body to the blocking Servlet API used by MVC request converters.
     */
    private static final class BodyInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        /**
         * Opens a stream over bytes that have already passed the request budget.
         *
         * @param body accepted HTTP body bytes
         */
        private BodyInputStream(byte[] body) {
            input = new ByteArrayInputStream(body);
        }

        /**
         * Reads the next buffered byte.
         *
         * @return the next unsigned byte, or minus one at end of input
         */
        @Override
        public int read() {
            return input.read();
        }

        /**
         * Copies buffered bytes without per-byte Servlet overhead.
         *
         * @param target destination buffer
         * @param offset first destination index
         * @param length maximum bytes to copy
         * @return copied byte count, or minus one at end of input
         */
        @Override
        public int read(@NonNull byte[] target, int offset, int length) {
            return input.read(target, offset, length);
        }

        /**
         * Indicates whether all admitted bytes have been consumed.
         *
         * @return true at end of the buffered input
         */
        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        /**
         * Indicates that reading buffered bytes never waits for network input.
         *
         * @return true because the body is held in memory
         */
        @Override
        public boolean isReady() {
            return true;
        }

        /**
         * Rejects asynchronous consumption, which this synchronous MVC API does not use.
         *
         * @param listener nonblocking listener that cannot be registered on this synchronous stream
         * @throws IllegalStateException because asynchronous request reads are not supported
         */
        @Override
        public void setReadListener(ReadListener listener) {
            throw new IllegalStateException("Asynchronous request reads are not supported");
        }
    }
}
