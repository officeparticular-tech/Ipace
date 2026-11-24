package pt.ipacediag.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Barra horizontal de temperatura, do estilo termómetro: fundo cinzento, enchimento
 * colorido até ao valor, e marcas nas fronteiras das zonas (fria / ideal / intermédia / alta).
 * A cor do enchimento vem de Theme.tempColor, para ficar coerente com o texto do valor.
 */
public final class TempBar extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private double min = -20, max = 60, value = Double.NaN;
    private int color = Theme.MUTED;

    public TempBar(Context c) {
        super(c);
        track.setColor(Theme.SURFACE_2);
        tick.setColor(Theme.STROKE);
        tick.setStrokeWidth(Math.max(1, Theme.dp(c, 1)));
    }

    public void set(double celsius, int col) { value = celsius; color = col; invalidate(); }

    public void setRange(double lo, double hi) { min = lo; max = hi; invalidate(); }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Theme.dp(getContext(), 14));
    }

    @Override protected void onDraw(Canvas c) {
        float h = getHeight(), w = getWidth(), r = h / 2f;
        box.set(0, 0, w, h);
        track.setColor(Theme.SURFACE_2);
        c.drawRoundRect(box, r, r, track);
        if (!Double.isNaN(value)) {
            double f = Math.max(0, Math.min(1, (value - min) / (max - min)));
            fill.setColor(color);
            float fw = (float) Math.max(h, w * f);
            c.drawRoundRect(new RectF(0, 0, fw, h), r, r, fill);
        }
        double[] marks = {10, 35, 45};
        for (double m : marks) {
            float x = (float) (w * (m - min) / (max - min));
            c.drawLine(x, 2, x, h - 2, tick);
        }
    }
}
