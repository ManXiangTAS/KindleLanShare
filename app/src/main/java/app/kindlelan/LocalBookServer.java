package app.kindlelan;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A zero-dependency, temporary HTTP server. Works on Android and plain JVM. */
public final class LocalBookServer implements AutoCloseable {
    public interface Book {
        String id();
        String name();
        long size(); // -1 when the document provider does not expose a length
        InputStream open() throws IOException;
    }

    private final List<Book> books;
    private final Map<String, Book> byId;
    private final String secret;
    private final ThreadPoolExecutor workers;
    private final Set<Socket> openSockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private volatile boolean running;
    private ServerSocket listener;
    private Thread acceptThread;

    public LocalBookServer(List<? extends Book> selectedBooks) {
        books = Collections.unmodifiableList(new ArrayList<Book>(selectedBooks));
        byId = new LinkedHashMap<String, Book>();
        for (Book book : books) {
            if (book.id() == null || !book.id().matches("[a-zA-Z0-9_-]+"))
                throw new IllegalArgumentException("Unsafe book ID");
            if (byId.put(book.id(), book) != null)
                throw new IllegalArgumentException("Duplicate book ID");
        }
        byte[] random = new byte[6]; // 48-bit temporary access token, easier to type on a Kindle.
        new SecureRandom().nextBytes(random);
        secret = toHex(random);
        workers = new ThreadPoolExecutor(2, 4, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(24), runnable -> {
                    Thread t = new Thread(runnable, "kindle-http-worker");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    private static String toHex(byte[] bytes) {
        final char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 255;
            out[2 * i] = hex[b >>> 4];
            out[2 * i + 1] = hex[b & 15];
        }
        return new String(out);
    }

    public synchronized void start() throws IOException {
        if (running) throw new IllegalStateException("Already started");
        listener = new ServerSocket(0, 25, InetAddress.getByName("0.0.0.0"));
        listener.setSoTimeout(1000);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "kindle-http-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = listener.accept();
                client.setSoTimeout(15000);
                openSockets.add(client);
                try {
                    workers.execute(() -> handle(client));
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    openSockets.remove(client);
                    try { client.close(); } catch (IOException ignored) { }
                }
            } catch (java.net.SocketTimeoutException ignored) {
                // Periodically check whether the server was stopped.
            } catch (SocketException e) {
                if (running) e.printStackTrace();
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
    }

    public int port() { return listener == null ? -1 : listener.getLocalPort(); }
    public String secret() { return secret; }
    public String url(String ip) { return "http://" + ip + ":" + port() + "/" + secret + "/"; }

    private void handle(Socket socket) {
        try (Socket s = socket;
             InputStream input = new BufferedInputStream(s.getInputStream());
             OutputStream output = new BufferedOutputStream(s.getOutputStream(), 65536)) {
            String requestLine = readLine(input);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ", 3);
            if (parts.length != 3) { error(output, 400, "Bad Request", "Invalid HTTP request"); return; }
            String method = parts[0];
            if (!method.equals("GET") && !method.equals("HEAD")) {
                error(output, 405, "Method Not Allowed", "Only GET and HEAD are supported");
                return;
            }
            boolean head = method.equals("HEAD");
            String path = parts[1].split("\\?", 2)[0];
            String range = null;
            int count = 0;
            while (true) {
                String line = readLine(input);
                if (line == null) return;
                if (line.length() == 0) break;
                if (++count > 80) { error(output, 431, "Request Header Fields Too Large", "Too many headers"); return; }
                if (line.regionMatches(true, 0, "Range:", 0, 6)) range = line.substring(6).trim();
            }
            if (path.equals("/" + secret + "/") || path.equals("/" + secret)) {
                index(output, head);
                return;
            }
            String prefix = "/" + secret + "/b/";
            if (!path.startsWith(prefix)) {
                error(output, 404, "Not Found", "Link expired or unavailable");
                return;
            }
            Book book = byId.get(path.substring(prefix.length()));
            if (book == null) { error(output, 404, "Not Found", "Book not found"); return; }
            download(output, book, range, head);
        } catch (Exception e) {
            // Kindle may close the connection during a download; don't crash the server.
        } finally {
            openSockets.remove(socket);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder(96);
        while (true) {
            int x = in.read();
            if (x == -1) return b.length() == 0 ? null : b.toString();
            if (x == '\n') {
                if (b.length() > 0 && b.charAt(b.length() - 1) == '\r') b.setLength(b.length() - 1);
                return b.toString();
            }
            if (b.length() >= 8192) throw new IOException("Request line too long");
            b.append((char) (x & 255));
        }
    }

    // Limit response sizes only to file lengths, never to a fixed application maximum.
    private void download(OutputStream output, Book book, String range, boolean head) throws IOException {
        long size = book.size();
        long from = 0;
        long to = size >= 0 ? size - 1 : -1;
        boolean partial = false;
        if (range != null && size >= 0) {
            long[] parsed = parseRange(range, size);
            if (parsed == null) {
                writeHeaders(output, 416, "Range Not Satisfiable", 0, "text/plain; charset=utf-8",
                        "Content-Range: bytes */" + size + "\r\n");
                output.flush();
                return;
            }
            from = parsed[0];
            to = parsed[1];
            partial = true;
        }
        long length = size >= 0 ? (size == 0 ? 0 : to - from + 1) : -1;
        String extensionMime = mimeFor(book.name());
        String fileName = safeAsciiName(book.name());
        String utfName = percentEncode(book.name());
        String headers = "Content-Disposition: attachment; filename=\"" + fileName +
                "\"; filename*=UTF-8''" + utfName + "\r\n" +
                (size >= 0 ? "Accept-Ranges: bytes\r\n" : "") +
                (partial ? "Content-Range: bytes " + from + "-" + to + "/" + size + "\r\n" : "");
        // Open before sending success: if a cloud provider fails, return an HTTP error.
        if (head) {
            writeHeaders(output, partial ? 206 : 200, partial ? "Partial Content" : "OK", length,
                    extensionMime, headers);
            output.flush();
            return;
        }
        InputStream file = null;
        try {
            file = book.open();
            if (file == null) throw new IOException("File provider returned no stream");
            skipExactly(file, from);
        } catch (IOException e) {
            if (file != null) try { file.close(); } catch (IOException ignored) { }
            error(output, 500, "Internal Server Error", "Cannot read the selected file");
            return;
        }
        try (InputStream source = file) {
            writeHeaders(output, partial ? 206 : 200, partial ? "Partial Content" : "OK", length,
                    extensionMime, headers);
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining != 0) {
                int want = remaining < 0 ? buffer.length : (int) Math.min(buffer.length, remaining);
                int n = source.read(buffer, 0, want);
                if (n == -1) break;
                if (n == 0) continue;
                output.write(buffer, 0, n);
                if (remaining > 0) remaining -= n;
            }
            output.flush();
        }
    }

    private static void skipExactly(InputStream stream, long count) throws IOException {
        byte[] discard = new byte[8192];
        while (count > 0) {
            long skipped = stream.skip(count);
            if (skipped > 0) { count -= skipped; continue; }
            int n = stream.read(discard, 0, (int) Math.min(discard.length, count));
            if (n == -1) throw new IOException("Stream too short for Range request");
            count -= n;
        }
    }

    private static long[] parseRange(String input, long size) {
        if (size <= 0 || !input.startsWith("bytes=") || input.indexOf(',') >= 0) return null;
        String r = input.substring(6);
        int dash = r.indexOf('-');
        if (dash < 0 || dash != r.lastIndexOf('-')) return null;
        try {
            String a = r.substring(0, dash).trim();
            String b = r.substring(dash + 1).trim();
            long start, end;
            if (a.isEmpty()) {
                long suffix = Long.parseLong(b);
                if (suffix <= 0) return null;
                start = size - Math.min(suffix, size);
                end = size - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? size - 1 : Long.parseLong(b);
                if (start < 0 || start >= size || end < start) return null;
                end = Math.min(end, size - 1);
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void index(OutputStream output, boolean head) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>Kindle 传书</title>")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>")
                .append("<body bgcolor=\"white\" text=\"black\"><h2>Kindle 传书</h2>")
                .append("<p>点击书名下载到设备。请保持手机上的共享处于开启状态。</p><hr>")
                .append("<ol>");
        for (Book book : books) {
            html.append("<li><p><a href=\"/" + secret + "/b/").append(book.id()).append("\">")
                    .append(escapeHtml(book.name())).append("</a>");
            if (book.size() >= 0) html.append(" (" + humanSize(book.size()) + ")");
            html.append("</p></li>");
        }
        html.append("</ol><hr><p>提示：能否下载和打开 EPUB、PDF、AZW3 等格式取决于 Kindle 型号与固件；部分机型仅支持从浏览器下载 MOBI 等特定格式。</p>")
                .append("</body></html>");
        byte[] bytes = html.toString().getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, 200, "OK", bytes.length, "text/html; charset=utf-8", "Cache-Control: no-store\r\n");
        if (!head) output.write(bytes);
        output.flush();
    }

    private static String humanSize(long n) {
        if (n < 1024) return n + " B";
        double x = n;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (x >= 1024 && i < 4) { x /= 1024; i++; }
        return String.format(Locale.US, "%.1f %s", x, units[i]);
    }

    private static String escapeHtml(String x) {
        return x.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String safeAsciiName(String input) {
        String s = input == null ? "book" : input;
        String extension = "";
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) {
            String candidate = s.substring(dot).toLowerCase(Locale.US);
            if (candidate.matches("\\.[a-z0-9]{1,10}")) extension = candidate;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length() && out.length() < 100; i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '.' || c == '-' || c == '_' || c == ' ') out.append(c);
            else if (c > 127) out.append('_');
        }
        String result = out.toString().trim();
        if (result.replaceAll("[_.\\s]", "").isEmpty()) result = "book";
        if (!extension.isEmpty() && !result.toLowerCase(Locale.US).endsWith(extension)) result += extension;
        return result;
    }

    private static String percentEncode(String s) {
        try { return URLEncoder.encode(s == null ? "book" : s, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { return "book"; }
    }

    private static String mimeFor(String name) {
        String n = (name == null ? "" : name).toLowerCase(Locale.US);
        if (n.endsWith(".mobi") || n.endsWith(".prc")) return "application/x-mobipocket-ebook";
        if (n.endsWith(".azw") || n.endsWith(".azw3") || n.endsWith(".kfx")) return "application/vnd.amazon.ebook";
        if (n.endsWith(".epub")) return "application/epub+zip";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private static void error(OutputStream output, int code, String status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, code, status, body.length, "text/plain; charset=utf-8", "Cache-Control: no-store\r\n");
        output.write(body);
        output.flush();
    }

    private static void writeHeaders(OutputStream out, int code, String reason, long length,
                                     String mime, String other) throws IOException {
        String h = "HTTP/1.1 " + code + " " + reason + "\r\n" +
                "Content-Type: " + mime + "\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                (length >= 0 ? "Content-Length: " + length + "\r\n" : "") +
                "Connection: close\r\n" + other + "\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Override public synchronized void close() {
        running = false;
        try { if (listener != null) listener.close(); } catch (IOException ignored) { }
        // Invalidate the session and terminate any in-progress download immediately.
        for (Socket client : openSockets) {
            try { client.close(); } catch (IOException ignored) { }
        }
        workers.shutdownNow();
        if (acceptThread != null) acceptThread.interrupt();
    }
}
