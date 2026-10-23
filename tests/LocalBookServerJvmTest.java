import app.kindlelan.LocalBookServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Run on a computer with plain JDK 17: see README. */
public final class LocalBookServerJvmTest {
    static final byte[] SAMPLE = "0123456789ABCDEFGHIJ".getBytes(StandardCharsets.US_ASCII);

    static final class Book implements LocalBookServer.Book {
        private final long size;
        Book(long size) { this.size = size; }
        public String id() { return "book1"; }
        public String name() { return "中文书籍.mobi"; }
        public long size() { return size; }
        public InputStream open() { return new ByteArrayInputStream(SAMPLE); }
    }

    static final class Response {
        int status;
        Map<String, String> headers = new HashMap<>();
        byte[] body;
    }

    static Response request(int port, String method, String path, String extras) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(4000);
            OutputStream out = s.getOutputStream();
            out.write((method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n" + extras + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            Response r = new Response();
            String line = readLine(in);
            r.status = Integer.parseInt(line.split(" ")[1]);
            while (!(line = readLine(in)).isEmpty()) {
                int pos = line.indexOf(':');
                if (pos > 0) r.headers.put(line.substring(0, pos).toLowerCase(Locale.US), line.substring(pos + 1).trim());
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
            r.body = b.toByteArray();
            return r;
        }
    }
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int v;
        while ((v = in.read()) != -1 && v != '\n') if (v != '\r') b.write(v);
        return b.toString("UTF-8");
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        try (LocalBookServer server = new LocalBookServer(Collections.singletonList(new Book(SAMPLE.length)))) {
            server.start();
            String root = "/" + server.secret() + "/";
            Response index = request(server.port(), "GET", root, "");
            check(index.status == 200, "index HTTP status");
            check(new String(index.body, StandardCharsets.UTF_8).contains("中文书籍.mobi"), "UTF-8 book title");
            Response all = request(server.port(), "GET", root + "b/book1", "");
            check(all.status == 200, "book HTTP status");
            check(Arrays.equals(all.body, SAMPLE), "complete body");
            check(all.headers.get("content-disposition").contains(".mobi"), "file extension");
            Response range = request(server.port(), "GET", root + "b/book1", "Range: bytes=5-9\r\n");
            check(range.status == 206, "partial status");
            check(new String(range.body, StandardCharsets.US_ASCII).equals("56789"), "partial bytes");
            check(range.headers.get("content-range").equals("bytes 5-9/20"), "content-range");
            Response suffix = request(server.port(), "GET", root + "b/book1", "Range: bytes=-4\r\n");
            check(new String(suffix.body, StandardCharsets.US_ASCII).equals("GHIJ"), "suffix bytes");
            Response head = request(server.port(), "HEAD", root + "b/book1", "");
            check(head.body.length == 0 && head.headers.get("content-length").equals("20"), "HEAD");
            check(request(server.port(), "GET", "/incorrect/", "").status == 404, "temporary token");
            check(request(server.port(), "GET", root + "b/book1", "Range: bytes=999-\r\n").status == 416, "invalid range");
            check(request(server.port(), "POST", root, "").status == 405, "read-only server");
        }
        try (LocalBookServer server = new LocalBookServer(Collections.singletonList(new Book(3_000_000_000L)))) {
            server.start();
            Response head = request(server.port(), "HEAD", "/" + server.secret() + "/b/book1", "");
            check(head.headers.get("content-length").equals("3000000000"), "64-bit file length (>2 GB)");
        }
        System.out.println("PASS: HTTP index, Unicode, full download, Range, suffix, HEAD, token, methods, 64-bit length");
    }
}
