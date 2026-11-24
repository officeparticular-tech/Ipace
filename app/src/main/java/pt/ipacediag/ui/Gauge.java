package pt.ipacediag.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Mostrador circular (arco de 270°) com valor e etiqueta ao centro. */
public final class Gauge extends View {

    public interface ColorRule { int color(double v); }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final double min, max;
    private final String unit, label;
    private final int decimals;
    private ColorRule rule;
    private double value = Double.NaN;

    public Gauge(Context c, String label, String unit, double min, double max, int decimals, ColorRule rule) {
        super(c);
        this.label = label; this.unit = unit; this.min = min; this.max = max; this.decimals = decimals; this.rule = rule;
        float sw = Theme.dp(c, 10);
        track.setStyle(Paint.Style.STROKE); track.setStrokeWidth(sw); track.setStrokeCap(Paint.Cap.ROUND); track.setColor(Theme.SURFACE_2);
        arc.setStyle(Paint.Style.STROKE); arc.setStrokeWidth(sw); arc.setStrokeCap(Paint.Cap.ROUND);
        big.setColor(Theme.TEXT); big.setTextAlign(Paint.Align.CENTER); big.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        small.setColor(Theme.MUTED); small.setTextAlign(Paint.Align.CENTER);
    }

    public void setValue(double v) { value = v; invalidate(); }

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int size = Math.min(width, Theme.dp(getContext(), 170));
        setMeasuredDimension(width, size);
    }

    @Override protected void onDraw(Canvas c) {
        float h = getHeight(), w = getWidth();
        float pad = Theme.dp(getContext(), 10);
        float d = Math.min(w, h) - 2 * pad;
        float left = (w - d) / 2, top = pad;
        box.set(left, top, left + d, top + d);
        c.drawArc(box, 135, 270, false, track);
        if (!Double.isNaN(value)) {
            double f = Math.max(0, Math.min(1, (value - min) / (max - min)));
            arc.setColor(rule != null ? rule.color(value) : Theme.TEAL);
            c.drawArc(box, 135, (float) (270 * f), false, arc);
        }
        big.setTextSize(d * 0.22f);
        small.setTextSize(d * 0.09f);
        String v = Double.isNaN(value) ? "—" : String.format(java.util.Locale.US, "%." + decimals + "f", value);
        float cy = top + d / 2;
        c.drawText(v, w / 2, cy + d * 0.06f, big);
        c.drawText(unit, w / 2, cy + d * 0.19f, small);
        c.drawText(label, w / 2, top + d * 0.98f, small);
    }
}
