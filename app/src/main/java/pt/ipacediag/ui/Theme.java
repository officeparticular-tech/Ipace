package pt.ipacediag.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Paleta e componentes visuais da app (tema escuro, cartões arredondados). */
public final class Theme {
    private Theme() {}

    public static final int BG = Color.parseColor("#0B0F14");
    public static final int SURFACE = Color.parseColor("#151B23");
    public static final int SURFACE_2 = Color.parseColor("#1D2530");
    public static final int STROKE = Color.parseColor("#26303D");
    public static final int TEXT = Color.parseColor("#EEF2F7");
    public static final int MUTED = Color.parseColor("#8B97A8");
    public static final int GOLD = Color.parseColor("#D4B483");
    public static final int TEAL = Color.parseColor("#2ED3C6");
    public static final int OK = Color.parseColor("#3DDC84");
    public static final int WARN = Color.parseColor("#F5B942");
    public static final int BAD = Color.parseColor("#FF5C5C");
    public static final int INFO = Color.parseColor("#5AA9FF");

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable round(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    public static LinearLayout.LayoutParams match(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topDp);
        return lp;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        l.setBackground(round(c, SURFACE, STROKE, 18));
        l.setLayoutParams(match(c, 12));
        return l;
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    public static TextView title(Context c, String s) { return text(c, s, 18, TEXT, true); }
    public static TextView body(Context c, String s) { return text(c, s, 14, TEXT, false); }
    public static TextView muted(Context c, String s) { return text(c, s, 13, MUTED, false); }

    public static TextView mono(Context c, String s) {
        TextView t = text(c, s, 12, TEXT, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }

    /** Etiqueta pequena colorida (ex.: "CONFIRMADO", "DEMO"). */
    public static TextView pill(Context c, String s, int color) {
        TextView t = text(c, s, 11, color, true);
        t.setLetterSpacing(0.06f);
        int h = dp(c, 10), v = dp(c, 4);
        t.setPadding(h, v, h, v);
        t.setBackground(round(c, (color & 0x00FFFFFF) | 0x26000000, (color & 0x00FFFFFF) | 0x66000000, 999));
        return t;
    }

    public static Button button(Context c, String s, boolean primary) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? BG : TEXT);
        GradientDrawable bg = round(c, primary ? GOLD : SURFACE_2, primary ? 0 : STROKE, 14);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        b.setStateListAnimator(null);
        int p = dp(c, 14);
        b.setPadding(p, dp(c, 12), p, dp(c, 12));
        b.setMinHeight(dp(c, 48));
        b.setLayoutParams(match(c, 10));
        return b;
    }

    public static Button danger(Context c, String s) {
        Button b = button(c, s, false);
        b.setTextColor(BAD);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FF5C5C), round(c, 0x1AFF5C5C, 0x66FF5C5C, 14), null));
        return b;
    }

    public static EditText input(Context c, String hint, boolean number) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setSingleLine(true);
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        int p = dp(c, 12);
        e.setPadding(p, p, p, p);
        e.setBackground(round(c, SURFACE_2, STROKE, 12));
        e.setLayoutParams(match(c, 10));
        return e;
    }

    public static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setLayoutParams(match(c, 0));
        return r;
    }

    public static LinearLayout.LayoutParams weight(Context c, int leftMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(c, leftMarginDp);
        return lp;
    }

    /** Linha "nome ........ valor" usada nas listas de valores. */
    public static View kv(Context c, String k, String v, int valueColor) {
        LinearLayout r = row(c);
        r.setPadding(0, dp(c, 7), 0, dp(c, 7));
        TextView kt = text(c, k, 14, MUTED, false);
        kt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView vt = text(c, v, 15, valueColor, true);
        vt.setGravity(Gravity.END);
        r.addView(kt);
        r.addView(vt);
        return r;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(STROKE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1) / 2)));
        return v;
    }

    /**
     * Cor por zona de temperatura de uma bateria de iões de lítio:
     * fria (azul), ideal (verde), intermédia (âmbar), alta (vermelho).
     */
    public static int tempColor(double c) {
        if (Double.isNaN(c)) return MUTED;
        if (c < 10) return INFO;
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

    /** Cor de estado a partir de um texto de veredicto (BOM / ATENÇÃO / MAU ...). */
    public static int verdictColor(String s) {
        if (s == null) return MUTED;
        String u = s.toUpperCase();
        if (u.contains("MAU") || u.contains("ELEVADA") || u.contains("BAIXO") || u.contains("NÃO CHEGOU")) return BAD;
        if (u.contains("ATENÇÃO") || u.contains("FRACO") || u.contains("MODERADA") || u.contains("ACEITÁVEL") || u.contains("ALTO")) return WARN;
        if (u.contains("BOM") || u.contains("OK") || u.contains("NORMAL")) return OK;
        return INFO;
    }
}
