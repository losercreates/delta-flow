package io.deltaflow.idempotency.filter;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class CachedBodyHttpServletResponse extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    private ServletOutputStream servletOutputStream;
    private PrintWriter printWriter;

    public CachedBodyHttpServletResponse(HttpServletResponse response) {
        super(response);
    }

    @Override
    public ServletOutputStream getOutputStream() {
        if (printWriter != null) {
            throw new IllegalStateException("getWriter() has already been called on this response.");
        }
        if (servletOutputStream == null) {
            servletOutputStream = new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener writeListener) {
                }

                @Override
                public void write(int b) {
                    outputStream.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    outputStream.write(b, off, len);
                }
            };
        }
        return servletOutputStream;
    }

    @Override
    public PrintWriter getWriter() {
        if (servletOutputStream != null) {
            throw new IllegalStateException("getOutputStream() has already been called on this response.");
        }
        if (printWriter == null) {
            printWriter = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        }
        return printWriter;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (printWriter != null) {
            printWriter.flush();
        } else if (servletOutputStream != null) {
            servletOutputStream.flush();
        }
        super.flushBuffer();
    }

    public byte[] getCapturedBody() {
        if (printWriter != null) {
            printWriter.flush();
        }
        return outputStream.toByteArray();
    }

    public Map<String, String> getCapturedHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        Collection<String> headerNames = getHeaderNames();
        if (headerNames != null) {
            for (String name : headerNames) {
                headers.put(name, getHeader(name));
            }
        }
        return headers;
    }

    public void copyBodyToResponse() throws IOException {
        byte[] body = getCapturedBody();
        if (body.length > 0) {
            getResponse().getOutputStream().write(body);
            getResponse().getOutputStream().flush();
        }
    }
}

