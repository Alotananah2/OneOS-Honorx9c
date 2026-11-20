package com.murdhiias.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.View;
import java.util.Calendar;
import java.util.Locale;

/** The big split clock: hours filled, minutes outlined, tall condensed digits. */
public class SplitClock extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    public SplitClock(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        Typeface cond = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        fill.setTypeface(cond);
        fill.setColor(0xFFFFFFFF);
        stroke.setTypeface(cond);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2.2f * dp);
        small.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        small.setColor(0xCCFFFFFF);
        small.setTextSize(14 * dp);
        small.setLetterSpacing(0.08f);
        fill.setShadowLayer(12 * dp, 0, 0, 0x55000000);
    }

    private static String part(Calendar c) {
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (h < 5) return "Night";
        if (h < 12) return "Morning";
        if (h < 17) return "Afternoon";
        if (h < 21) return "Evening";
        return "Night";
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Calendar c = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        int h = c.get(h24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (!h24 && h == 0) h = 12;
        String hh = String.format(Locale.US, "%02d", h);
        String mm = String.format(Locale.US, "%02d", c.get(Calendar.MINUTE));
        float w = getWidth(), ht = getHeight();
        float dateH = 30 * dp;
        float size = (ht - dateH) * 1.18f;
        fill.setTextSize(size);
        stroke.setTextSize(size);
        float total = fill.measureText(hh) + stroke.measureText(mm);
        if (total > w * 0.94f) {
            size = size * (w * 0.94f) / total;
            fill.setTextSize(size);
            stroke.setTextSize(size);
            total = fill.measureText(hh) + stroke.measureText(mm);
        }
        Rect b = new Rect();
        fill.getTextBounds("00", 0, 2, b);
        float x = (w - total) / 2f;
        float y = (ht - dateH) / 2f + b.height() / 2f;
        canvas.drawText(hh, x, y, fill);
        canvas.drawText(mm, x + fill.measureText(hh), y, stroke);
        String date = String.format(Locale.US, "%02d / %02d / %s / %s",
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US), part(c));
        canvas.drawText(date, (w - small.measureText(date)) / 2f, ht - 8 * dp, small);
    }
}
