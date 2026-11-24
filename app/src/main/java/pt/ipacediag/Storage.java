package pt.ipacediag;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Grava ficheiros em Transferências/IPaceDiag (visíveis na app "Os meus ficheiros"). */
public final class Storage {
    private Storage() {}

    public static final String FOLDER = "Download/IPaceDiag";

    public static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private static Uri create(Context c, String name, String mime) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
        Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Não foi possível criar " + name);
        return uri;
    }

    public static String saveText(Context c, String name, String text) throws IOException {
        Uri u = create(c, name, name.endsWith(".csv") ? "text/csv" : "text/plain");
        try (OutputStream o = c.getContentResolver().openOutputStream(u)) {
            if (o == null) throw new IOException("sem acesso");
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return FOLDER + "/" + name;
    }

    /** Ficheiro CSV escrito linha a linha (cada linha é gravada logo, para não perder dados). */
    public static final class LineFile implements AutoCloseable {
        private final OutputStream o;
        public final String path;
        public LineFile(Context c, String name) throws IOException {
            Uri u = create(c, name, name.endsWith(".csv") ? "text/csv" : "text/plain");
            ContentResolver cr = c.getContentResolver();
            o = cr.openOutputStream(u);
            if (o == null) throw new IOException("sem acesso");
            path = FOLDER + "/" + name;
        }
        public synchronized void line(String s) {
            try {
                o.write((s + "\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
            } catch (IOException ignored) { }
        }
        @Override public synchronized void close() {
            try { o.close(); } catch (IOException ignored) { }
        }
    }

    /** Converte o relatório de texto num PDF A4 simples. */
    public static String savePdf(Context c, String name, String text) throws IOException {
        PdfDocument doc = new PdfDocument();
        Paint p = new Paint();
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(8.5f);
        int pageW = 595, pageH = 842, margin = 36;
        float lineH = 11f;
        int maxChars = (int) ((pageW - 2 * margin) / p.measureText("M"));
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            String l = raw;
            while (l.length() > maxChars) {
                int cut = l.lastIndexOf(' ', maxChars);
                if (cut < maxChars / 2) cut = maxChars;
                lines.add(l.substring(0, cut));
                l = "    " + l.substring(cut).trim();
            }
            lines.add(l);
        }
        int perPage = (int) ((pageH - 2 * margin) / lineH);
        int pageNo = 1;
        for (int i = 0; i < lines.size(); i += perPage) {
            PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(pageW, pageH, pageNo++).create());
            float y = margin;
            for (int j = i; j < Math.min(lines.size(), i + perPage); j++) {
                page.getCanvas().drawText(lines.get(j), margin, y, p);
                y += lineH;
            }
            doc.finishPage(page);
        }
        Uri u = create(c, name, "application/pdf");
        try (OutputStream o = c.getContentResolver().openOutputStream(u)) {
            if (o == null) throw new IOException("sem acesso");
            doc.writeTo(o);
        } finally {
            doc.close();
        }
        return FOLDER + "/" + name;
    }
}
