package app.kindlelan;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final int PICK_BOOKS = 101;
    private final ArrayList<LocalBookServer.Book> selectedBooks = new ArrayList<>();
    private final Set<String> selectedUris = new HashSet<>();
    private LocalBookServer server;
    private TextView state;
    private TextView address;
    private TextView filenames;
    private Button startStop;
    private Button copy;
    private String currentUrl = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        renderUi();
        redraw();
    }

    private void renderUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(20), dp(26), dp(20), dp(26));
        scroll.addView(main);

        TextView title = text("Kindle 局域网传书", 25, Color.BLACK, true);
        main.addView(title);
        TextView subtitle = text("选择书籍 → 开启共享 → 用 Kindle 浏览器访问网址。\n不经过云端，不限定单本书大小。", 15, 0xff444444, false);
        LinearLayout.LayoutParams sp = params(0, 20);
        main.addView(subtitle, sp);

        Button choose = button("＋ 选择手机里的书籍");
        choose.setOnClickListener(v -> selectDocuments());
        main.addView(choose, params(0, 9));

        Button clear = button("清空书籍列表");
        clear.setOnClickListener(v -> {
            stopSharing();
            selectedBooks.clear();
            selectedUris.clear();
            redraw();
        });
        main.addView(clear, params(8, 12));

        filenames = text("", 15, Color.BLACK, false);
        filenames.setPadding(dp(10), dp(12), dp(10), dp(12));
        filenames.setBackground(border());
        main.addView(filenames, params(5, 16));

        startStop = button("开启临时共享");
        startStop.setOnClickListener(v -> {
            if (server == null) startSharing();
            else stopSharing();
            redraw();
        });
        main.addView(startStop, params(0, 14));

        state = text("", 15, 0xff222222, true);
        main.addView(state, params(10, 5));
        address = text("", 18, 0xff000000, true);
        address.setTextIsSelectable(true);
        address.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        address.setPadding(dp(12), dp(16), dp(12), dp(16));
        address.setBackground(border());
        main.addView(address, params(0, 10));

        copy = button("复制网址");
        copy.setOnClickListener(v -> {
            if (currentUrl.isEmpty()) return;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Kindle URL", currentUrl));
            Toast.makeText(this, "网址已复制", Toast.LENGTH_SHORT).show();
        });
        main.addView(copy, params(0, 18));

        TextView tip = text("使用说明\n① 手机和 Kindle 连接同一 Wi‑Fi（或 Kindle 连到手机热点）。\n② Kindle 打开浏览器，输入上方完整的 http:// 地址。\n③ 点击书名即可下载。传输期间保持此页面亮屏。\n\n注意：Kindle 浏览器支持的下载格式随机型/固件而异；有些型号不能直接下载 EPUB、PDF 或 AZW3。该应用不自动转换格式，也不保证突破 Kindle 自身的文件限制。共享仅在当前局域网内开放。", 14, 0xff444444, false);
        main.addView(tip);
        setContentView(scroll);
    }

    private void selectDocuments() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_BOOKS);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_BOOKS || resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); ++i) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        stopSharing(); // Refresh the private URL whenever the shared set changes.
        int added = 0;
        for (Uri uri : uris) {
            if (uri == null || selectedUris.contains(uri.toString())) continue;
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException | UnsupportedOperationException ignored) {
                // The temporary URI permission from the file picker is sufficient for this session.
            }
            selectedBooks.add(new UriBook(uri));
            selectedUris.add(uri.toString());
            added++;
        }
        Toast.makeText(this, "已添加 " + added + " 本文件", Toast.LENGTH_SHORT).show();
        redraw();
    }

    private void startSharing() {
        if (selectedBooks.isEmpty()) return;
        ArrayList<String> ips = localIPv4();
        if (ips.isEmpty()) {
            Toast.makeText(this, "未找到 Wi‑Fi/热点局域网地址。请先连接网络。", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            LocalBookServer running = new LocalBookServer(selectedBooks);
            running.start();
            server = running;
            currentUrl = running.url(ips.get(0));
        } catch (IOException e) {
            Toast.makeText(this, "开启失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopSharing() {
        if (server != null) {
            server.close();
            server = null;
        }
        currentUrl = "";
    }

    private void redraw() {
        StringBuilder b = new StringBuilder("书籍列表（" + selectedBooks.size() + " 个）\n");
        for (int i = 0; i < selectedBooks.size(); ++i) {
            LocalBookServer.Book item = selectedBooks.get(i);
            b.append('\n').append(i + 1).append(". ").append(item.name());
            if (item.size() >= 0) b.append("（").append(sizeText(item.size())).append("）");
        }
        if (selectedBooks.isEmpty()) b.append("\n还没有选择书籍");
        filenames.setText(b.toString());
        boolean active = server != null;
        state.setText(active ? "● 正在共享（仅当前局域网）" : "○ 尚未开启共享");
        address.setText(active ? currentUrl : "开启共享后，这里会显示 Kindle 访问网址");
        startStop.setText(active ? "■ 关闭共享（立即失效）" : "▶ 开启临时共享");
        startStop.setEnabled(active || !selectedBooks.isEmpty());
        copy.setEnabled(active);
    }

    private static ArrayList<String> localIPv4() {
        ArrayList<String> options = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return options;
            while (en.hasMoreElements()) {
                NetworkInterface iface = en.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress ip = addresses.nextElement();
                    if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) {
                        // Prefer Wi-Fi / phone-hotspot interfaces over VPNs and cellular routing.
                        if (iface.getName().startsWith("wlan") || iface.getName().startsWith("ap")
                                || iface.getName().startsWith("swlan")) options.add(0, ip.getHostAddress());
                        else options.add(ip.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) { }
        return options;
    }

    private final class UriBook implements LocalBookServer.Book {
        final Uri uri;
        final String id;
        final String name;
        final long length;

        UriBook(Uri uri) {
            this.uri = uri;
            this.id = UUID.randomUUID().toString().replace("-", "");
            String display = uri.getLastPathSegment() == null ? "book" : uri.getLastPathSegment();
            long bytes = -1;
            try (Cursor c = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIndex = c.getColumnIndex(OpenableColumns.SIZE);
                    if (nameIndex >= 0 && !c.isNull(nameIndex)) display = c.getString(nameIndex);
                    if (sizeIndex >= 0 && !c.isNull(sizeIndex)) bytes = c.getLong(sizeIndex);
                }
            } catch (Exception ignored) { }
            name = display == null ? "book" : display.replace('\r', ' ').replace('\n', ' ');
            length = bytes;
        }
        @Override public String id() { return id; }
        @Override public String name() { return name; }
        @Override public long size() { return length; }
        @Override public InputStream open() throws IOException {
            InputStream stream = getContentResolver().openInputStream(uri);
            if (stream == null) throw new IOException("无法打开书籍：" + name);
            return stream;
        }
    }

    private static String sizeText(long n) {
        if (n < 1024) return n + " B";
        double v = n;
        String[] unit = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < unit.length - 1) { v /= 1024; i++; }
        return String.format(java.util.Locale.US, "%.1f %s", v, unit[i]);
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams params(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        return p;
    }
    private TextView text(String message, int size, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextColor(color);
        tv.setTextSize(size);
        tv.setLineSpacing(dp(4), 1.0f);
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }
    private Button button(String name) {
        Button b = new Button(this);
        b.setText(name);
        b.setTextSize(15);
        b.setAllCaps(false);
        return b;
    }
    private GradientDrawable border() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setStroke(dp(1), 0xffaaaaaa);
        bg.setCornerRadius(0);
        return bg;
    }

    @Override protected void onDestroy() {
        stopSharing();
        super.onDestroy();
    }
}
