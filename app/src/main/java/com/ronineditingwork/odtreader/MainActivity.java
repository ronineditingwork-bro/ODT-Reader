package com.ronineditingwork.odtreader;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Xml;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    private static final int REQUEST_OPEN_ODT = 1001;
    private static final String ODT_MIME = "application/vnd.oasis.opendocument.text";

    private TextView documentTitle;
    private TextView documentText;
    private LinearLayout root;
    private boolean darkMode;
    private float fontSizeSp;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("reader_settings", MODE_PRIVATE);
        darkMode = prefs.getBoolean("dark_mode", false);
        fontSizeSp = prefs.getFloat("font_size", 18f);

        buildUi();
        applyTheme();

        Intent incoming = getIntent();
        if (incoming != null && Intent.ACTION_VIEW.equals(incoming.getAction()) && incoming.getData() != null) {
            openOdt(incoming.getData());
        }
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(16));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        Button openButton = new Button(this);
        openButton.setText("Открыть ODT");
        openButton.setOnClickListener(v -> chooseOdt());

        Button minusButton = new Button(this);
        minusButton.setText("A−");
        minusButton.setOnClickListener(v -> changeFontSize(-2f));

        Button plusButton = new Button(this);
        plusButton.setText("A+");
        plusButton.setOnClickListener(v -> changeFontSize(2f));

        Button themeButton = new Button(this);
        themeButton.setText("◐");
        themeButton.setContentDescription("Сменить тему");
        themeButton.setOnClickListener(v -> {
            darkMode = !darkMode;
            prefs.edit().putBoolean("dark_mode", darkMode).apply();
            applyTheme();
        });

        toolbar.addView(openButton, new LinearLayout.LayoutParams(0, dp(52), 1f));
        toolbar.addView(minusButton, new LinearLayout.LayoutParams(dp(70), dp(52)));
        toolbar.addView(plusButton, new LinearLayout.LayoutParams(dp(70), dp(52)));
        toolbar.addView(themeButton, new LinearLayout.LayoutParams(dp(64), dp(52)));

        documentTitle = new TextView(this);
        documentTitle.setText("Выберите ODT-файл");
        documentTitle.setTextSize(16f);
        documentTitle.setPadding(0, dp(14), 0, dp(10));

        documentText = new TextView(this);
        documentText.setText("Нажмите «Открыть ODT» и выберите документ в памяти телефона.");
        documentText.setTextSize(fontSizeSp);
        documentText.setTextIsSelectable(true);
        documentText.setLineSpacing(0f, 1.16f);
        documentText.setPadding(dp(2), dp(4), dp(2), dp(24));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(documentText, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        root.addView(toolbar);
        root.addView(documentTitle);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        setContentView(root);
    }

    private void chooseOdt() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(ODT_MIME);
        try {
            startActivityForResult(intent, REQUEST_OPEN_ODT);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("*/*");
            startActivityForResult(fallback, REQUEST_OPEN_ODT);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN_ODT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(
                        uri,
                        data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                );
            } catch (Exception ignored) {
            }
            openOdt(uri);
        }
    }

    private void openOdt(Uri uri) {
        documentTitle.setText(getDisplayName(uri));
        documentText.setText("Открываю документ…");

        new Thread(() -> {
            try {
                String text = readOdtText(uri);
                runOnUiThread(() -> {
                    documentText.setText(text.isEmpty() ? "В документе не найден текст." : text);
                    documentText.scrollTo(0, 0);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    documentText.setText("Не удалось открыть документ.");
                    Toast.makeText(
                            MainActivity.this,
                            "Файл повреждён или имеет неподдерживаемый формат.",
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        }).start();
    }

    private String readOdtText(Uri uri) throws Exception {
        ContentResolver resolver = getContentResolver();
        InputStream input = resolver.openInputStream(uri);
        if (input == null) throw new IllegalStateException("Cannot open input stream");

        byte[] contentXml = null;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("content.xml".equals(entry.getName())) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    contentXml = out.toByteArray();
                    break;
                }
            }
        }

        if (contentXml == null) {
            throw new IllegalArgumentException("content.xml not found");
        }

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new ByteArrayInputStream(contentXml), "UTF-8");

        StringBuilder out = new StringBuilder();
        int event;
        boolean lastWasBlockStart = false;

        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = localName(parser.getName());

                if ("p".equals(name) || "h".equals(name) || "list-item".equals(name)) {
                    appendBlockBreak(out);
                    lastWasBlockStart = true;
                } else if ("tab".equals(name)) {
                    out.append('\t');
                } else if ("line-break".equals(name)) {
                    out.append('\n');
                } else if ("s".equals(name)) {
                    int count = 1;
                    for (int i = 0; i < parser.getAttributeCount(); i++) {
                        if ("c".equals(localName(parser.getAttributeName(i)))) {
                            try {
                                count = Integer.parseInt(parser.getAttributeValue(i));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    for (int i = 0; i < count; i++) out.append(' ');
                }
            } else if (event == XmlPullParser.TEXT) {
                String value = parser.getText();
                if (value != null) {
                    out.append(value);
                    lastWasBlockStart = false;
                }
            } else if (event == XmlPullParser.END_TAG) {
                String name = localName(parser.getName());
                if (("p".equals(name) || "h".equals(name) || "list-item".equals(name)) && !lastWasBlockStart) {
                    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                        out.append('\n');
                    }
                }
            }
        }

        return cleanup(out.toString());
    }

    private void appendBlockBreak(StringBuilder out) {
        if (out.length() == 0) return;
        char last = out.charAt(out.length() - 1);
        if (last != '\n') out.append('\n');
    }

    private String cleanup(String value) {
        String normalized = value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n");
        return normalized.trim();
    }

    private String localName(String name) {
        if (name == null) return "";
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private String getDisplayName(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        String name = cursor.getString(index);
                        if (name != null && !name.isEmpty()) return name;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null ? "ODT-документ" : fallback;
    }

    private void changeFontSize(float delta) {
        fontSizeSp = Math.max(12f, Math.min(34f, fontSizeSp + delta));
        documentText.setTextSize(fontSizeSp);
        prefs.edit().putFloat("font_size", fontSizeSp).apply();
    }

    private void applyTheme() {
        int background = darkMode ? Color.rgb(20, 20, 20) : Color.rgb(250, 250, 250);
        int text = darkMode ? Color.rgb(238, 238, 238) : Color.rgb(30, 30, 30);
        int secondary = darkMode ? Color.rgb(190, 190, 190) : Color.rgb(90, 90, 90);

        root.setBackgroundColor(background);
        documentText.setTextColor(text);
        documentTitle.setTextColor(secondary);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
