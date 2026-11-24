package pt.ipacediag.ui;

import android.content.Context;
import android.content.ContentValues;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import pt.ipacediag.core.Diagnostics;
import pt.ipacediag.core.Dtc;
import pt.ipacediag.core.DtcAdvice;
import pt.ipacediag.core.DtcCatalog;

/**
 * Gera um relatório em PDF gráfico e a cores, pensado para ler e imprimir:
 * fundo claro, letra sans-serif legível, cabeçalho com as cores da app, cartões
 * de erro com cor por gravidade, e para cada erro as possíveis origens e soluções.
 */
public final class PdfReport {

    // Paleta clara (boa para impressão), derivada das cores da app
    private static final int INK = Color.parseColor("#1A2230");
    private static final int MUTED = Color.parseColor("#6B7280");
    private static final int GOLD = Color.parseColor("#9C7A2E");
    private static final int TEAL = Color.parseColor("#0F8E80");
    private static final int OK = Color.parseColor("#1E9E5A");
    private static final int WARN = Color.parseColor("#C77C0A");
    private static final int BAD = Color.parseColor("#D13838");
    private static final int CARD = Color.parseColor("#F5F7FA");
    private static final int STROKE = Color.parseColor("#E2E7EE");
    private static final int DARKBAND = Color.parseColor("#0E1420");

    private static final int W = 595, H = 842;
    private static final float M = 42, MAXY = H - 50;

    private final PdfDocument doc = new PdfDocument();
    private final Typeface reg = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface med = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String title, subtitle;

    private PdfDocument.Page page;
    private Canvas cv;
    private float y;
    private int pageNo = 0;

    public PdfReport(String title, String subtitle) {
        this.title = title;
        this.subtitle = subtitle;
        newPage();
        cover();
    }

    private void newPage() {
        if (page != null) { footer(); doc.finishPage(page); }
        pageNo++;
        page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
        cv = page.getCanvas();
        p.setColor(Color.WHITE);
        cv.drawRect(0, 0, W, H, p);
        p.setColor(GOLD);
        cv.drawRect(0, 0, W, 5, p);
        y = M;
    }

    private void need(float h) { if (y + h > MAXY) newPage(); }

    private void footer() {
        p.setTypeface(reg);
        p.setTextSize(7.5f);
        p.setColor(MUTED);
        p.setTextAlign(Paint.Align.LEFT);
        cv.drawText("i-Pace · ferramenta não oficial, sem garantias", M, H - 30, p);
        p.setTextAlign(Paint.Align.RIGHT);
        cv.drawText("Página " + pageNo, W - M, H - 30, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void cover() {
        p.setColor(DARKBAND);
        cv.drawRoundRect(new RectF(M, y, W - M, y + 92), 10, 10, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(med);
        p.setColor(GOLD);
        p.setTextSize(22);
        cv.drawText("I-Pace", M + 18, y + 34, p);
        p.setColor(Color.parseColor("#8B97A8"));
        p.setTypeface(reg);
        p.setTextSize(8);
        cv.drawText("HUGO VALVERDE · PT", M + 92, y + 34, p);
        p.setColor(Color.WHITE);
        p.setTypeface(med);
        p.setTextSize(13);
        cv.drawText(title, M + 18, y + 60, p);
        p.setColor(Color.parseColor("#B6C0CF"));
        p.setTypeface(reg);
        p.setTextSize(9.5f);
        cv.drawText(subtitle, M + 18, y + 78, p);
        y += 92 + 18;
    }

    public PdfReport line(String s) { paragraph(s, INK, 9.5f, false); return this; }

    public PdfReport section(String t, int color) {
        need(34);
        y += 8;
        p.setColor(color);
        cv.drawRoundRect(new RectF(M, y, M + 4, y + 16), 2, 2, p);
        p.setTypeface(med);
        p.setColor(INK);
        p.setTextSize(14);
        p.setTextAlign(Paint.Align.LEFT);
        cv.drawText(t, M + 12, y + 13, p);
        y += 24;
        return this;
    }

    public PdfReport section(String t) { return section(t, TEAL); }

    private float paragraph(String text, int color, float size, boolean bold) {
        p.setTypeface(bold ? med : reg);
        p.setTextSize(size);
        p.setColor(color);
        p.setTextAlign(Paint.Align.LEFT);
        float maxW = W - 2 * M;
        float start = y;
        for (String raw : text.split("\n")) {
            for (String ln : wrap(raw, maxW)) {
                need(size + 5);
                cv.drawText(ln, M, y + size, p);
                y += size + 5;
            }
        }
        return y - start;
    }

    private List<String> wrap(String s, float maxW) {
        List<String> out = new ArrayList<>();
        if (s.isEmpty()) { out.add(""); return out; }
        String[] words = s.split(" ");
        StringBuilder cur = new StringBuilder();
        for (String w : words) {
            String t = cur.length() == 0 ? w : cur + " " + w;
            if (p.measureText(t) > maxW && cur.length() > 0) { out.add(cur.toString()); cur = new StringBuilder(w); }
            else cur = new StringBuilder(t);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private void chip(float x, float cy, String text, int color) {
        p.setTypeface(med);
        p.setTextSize(8);
        float tw = p.measureText(text);
        RectF r = new RectF(x, cy - 10, x + tw + 16, cy + 3);
        p.setColor(tint(color));
        cv.drawRoundRect(r, 7, 7, p);
        p.setColor(color);
        cv.drawText(text, x + 8, cy, p);
    }

    private static int tint(int c) { return Color.argb(32, Color.red(c), Color.green(c), Color.blue(c)); }

    public PdfReport summary(List<Diagnostics.ModuleInfo> mods) {
        section("Resumo");
        int present = 0, withErr = 0, total = 0, lowV = 0;
        boolean hv = false;
        for (Diagnostics.ModuleInfo mi : mods) {
            if (!mi.responded) continue;
            present++;
            if (!mi.dtcs.isEmpty()) withErr++;
            for (Dtc d : mi.dtcs) { total++; if (DtcCatalog.isLowVoltageEvent(d)) lowV++; if (DtcCatalog.isHighVoltage(d)) hv = true; }
        }
        need(58);
        float boxW = (W - 2 * M - 24) / 3f;
        statBox(M, boxW, String.valueOf(present), "módulos", INK);
        statBox(M + boxW + 12, boxW, String.valueOf(total), "erros", total == 0 ? OK : WARN);
        statBox(M + 2 * (boxW + 12), boxW, String.valueOf(withErr), "módulos c/ erro", total == 0 ? OK : WARN);
        y += 58;
        if (hv) { banner("Há erros de ALTA TENSÃO. Guarde este relatório — é prova do defeito, útil para a garantia.", BAD); }
        if (lowV >= 2) { banner(lowV + " módulos com tensão 12V anormal (U3003): típico de uma 12V fraca. Ver secção 12V.", WARN); }
        return this;
    }

    private void statBox(float x, float w, String big, String lab, int color) {
        p.setColor(CARD);
        cv.drawRoundRect(new RectF(x, y, x + w, y + 48), 8, 8, p);
        p.setTypeface(med);
        p.setColor(color);
        p.setTextSize(22);
        p.setTextAlign(Paint.Align.LEFT);
        cv.drawText(big, x + 12, y + 28, p);
        p.setTypeface(reg);
        p.setColor(MUTED);
        p.setTextSize(8.5f);
        cv.drawText(lab, x + 12, y + 42, p);
    }

    private void banner(String text, int color) {
        p.setTypeface(reg);
        p.setTextSize(9);
        List<String> lines = wrap(text, W - 2 * M - 20);
        float h = lines.size() * 13 + 10;
        need(h + 6);
        p.setColor(tint(color));
        cv.drawRoundRect(new RectF(M, y, W - M, y + h), 8, 8, p);
        p.setColor(color);
        cv.drawRoundRect(new RectF(M, y, M + 4, y + h), 2, 2, p);
        p.setColor(darker(color));
        float ty = y + 15;
        for (String ln : lines) { cv.drawText(ln, M + 12, ty, p); ty += 13; }
        y += h + 6;
    }

    public PdfReport modules(List<Diagnostics.ModuleInfo> mods) {
        section("Erros por módulo", WARN);
        boolean anyErr = false;
        for (Diagnostics.ModuleInfo mi : mods) {
            if (!mi.responded || mi.dtcs.isEmpty()) continue;
            anyErr = true;
            for (Dtc d : mi.dtcs) dtcCard(mi.module.code, d);
        }
        if (!anyErr) paragraph("Sem erros nos módulos que responderam.", OK, 9.5f, false);
        return this;
    }

    private void dtcCard(String moduleCode, Dtc d) {
        int sev = d.testFailed() || DtcCatalog.isHighVoltage(d) ? BAD : d.confirmed() ? WARN : MUTED;
        DtcAdvice.Advice ad = DtcAdvice.forDtc(d);
        p.setTypeface(reg);
        p.setTextSize(9);
        float innerW = W - 2 * M - 28;
        float h = 44;
        h += wrap(DtcCatalog.describe(d), innerW).size() * 12;
        h += 14 + ad.causes.size() * 12 + 14 + ad.solutions.size() * 12 + 10;
        need(h + 8);
        float top = y;
        p.setColor(CARD);
        cv.drawRoundRect(new RectF(M, top, W - M, top + h), 9, 9, p);
        p.setColor(sev);
        cv.drawRoundRect(new RectF(M, top, M + 4, top + h), 2, 2, p);
        float x = M + 16;
        y = top + 20;
        p.setTypeface(med);
        p.setColor(INK);
        p.setTextSize(13);
        p.setTextAlign(Paint.Align.LEFT);
        cv.drawText(d.code(), x, y, p);
        float cw = p.measureText(d.code());
        p.setTypeface(reg);
        p.setColor(MUTED);
        p.setTextSize(9);
        cv.drawText(" · " + moduleCode, x + cw + 4, y, p);
        chip(W - M - 90, y, d.testFailed() ? "ATIVO" : d.confirmed() ? "CONFIRMADO" : d.pending() ? "PENDENTE" : "HISTÓRICO", sev);
        y += 8;
        indentParagraph(DtcCatalog.describe(d), INK, 9, x);
        y += 6;
        bulletTitle("Possíveis origens", GOLD, x);
        for (String c : ad.causes) bullet(c, x);
        y += 4;
        bulletTitle("Possíveis soluções", TEAL, x);
        for (String s : ad.solutions) bullet(s, x);
        y = top + h + 8;
    }

    private void indentParagraph(String text, int color, float size, float x) {
        p.setTypeface(reg);
        p.setTextSize(size);
        p.setColor(color);
        for (String ln : wrap(text, W - M - x - 12)) { cv.drawText(ln, x, y + size, p); y += size + 4; }
    }

    private void bulletTitle(String t, int color, float x) {
        p.setTypeface(med);
        p.setTextSize(9);
        p.setColor(color);
        cv.drawText(t, x, y + 9, p);
        y += 13;
    }

    private void bullet(String t, float x) {
        p.setTypeface(reg);
        p.setTextSize(9);
        p.setColor(INK);
        List<String> lines = wrap(t, W - M - (x + 12) - 8);
        for (int i = 0; i < lines.size(); i++) {
            if (i == 0) { p.setColor(MUTED); cv.drawText("•", x, y + 9, p); p.setColor(INK); }
            cv.drawText(lines.get(i), x + 12, y + 9, p);
            y += 12;
        }
    }

    public PdfReport kv(String k, String v, int color) {
        need(16);
        p.setTypeface(reg);
        p.setTextSize(9.5f);
        p.setColor(MUTED);
        p.setTextAlign(Paint.Align.LEFT);
        cv.drawText(k, M, y + 10, p);
        p.setTypeface(med);
        p.setColor(color);
        p.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(v, W - M, y + 10, p);
        p.setTextAlign(Paint.Align.LEFT);
        y += 16;
        return this;
    }

    private static final int BLUE = Color.parseColor("#2E6FB5");

    public static int tempCol(double c) {
        if (Double.isNaN(c)) return MUTED;
        if (c < 10) return BLUE;
        if (c <= 35) return OK;
        if (c < 45) return WARN;
        return BAD;
    }

    public static String tempZone(double c) {
        if (Double.isNaN(c)) return "—";
        if (c < 10) return "fria";
        if (c <= 35) return "ideal";
        if (c < 45) return "intermédia";
        return "alta";
    }

    public PdfReport tempBar(String label, double celsius) {
        return tempBar(label, celsius, tempCol(celsius), tempZone(celsius));
    }

    /** Barra de temperatura a cores (fria/ideal/intermédia/alta). */
    public PdfReport tempBar(String label, double celsius, int color, String zone) {
        need(30);
        p.setTypeface(reg);
        p.setTextSize(9.5f);
        p.setColor(MUTED);
        cv.drawText(label, M, y + 10, p);
        p.setTypeface(med);
        p.setColor(color);
        p.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(String.format(Locale.US, "%.1f °C · %s", celsius, zone), W - M, y + 10, p);
        p.setTextAlign(Paint.Align.LEFT);
        y += 15;
        float bw = W - 2 * M;
        p.setColor(STROKE);
        cv.drawRoundRect(new RectF(M, y, M + bw, y + 7), 3, 3, p);
        double f = Math.max(0, Math.min(1, (celsius + 20) / 80.0));
        p.setColor(color);
        cv.drawRoundRect(new RectF(M, y, M + (float) (bw * f), y + 7), 3, 3, p);
        y += 15;
        return this;
    }

    public PdfReport note(String text) { paragraph(text, MUTED, 8.5f, false); return this; }

    public String save(Context ctx, String filename) throws IOException {
        footer();
        doc.finishPage(page);
        page = null;
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/IPaceDiag");
        Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Não foi possível criar " + filename);
        try (OutputStream o = ctx.getContentResolver().openOutputStream(uri)) {
            if (o == null) throw new IOException("sem acesso");
            doc.writeTo(o);
        } finally { doc.close(); }
        return "Download/IPaceDiag/" + filename;
    }

    public static int colOk() { return OK; }
    public static int colWarn() { return WARN; }
    public static int colBad() { return BAD; }
    public static int colInk() { return INK; }
    public static int colInfo() { return Color.parseColor("#2E6FB5"); }

    private static int darker(int c) {
        return Color.rgb((int) (Color.red(c) * 0.7), (int) (Color.green(c) * 0.7), (int) (Color.blue(c) * 0.7));
    }

    public static String stampedDate() { return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()); }
}
