package com.carstream.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.io.ByteArrayOutputStream;

/** Local title cards keep artwork available without tablet Internet access. */
public final class StremioPoster {
    private StremioPoster() { }
    public static byte[] render(String title) {
        Bitmap bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.rgb(23, 32, 57));
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.rgb(130, 219, 196));
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            paint.setTextSize(23);
            canvas.drawText("CARSTREAM", 28, 48, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(30);
            String text = title.replaceAll("[._]+", " ").trim();
            float y = 115;
            while (!text.isEmpty() && y <= 307) {
                int count = paint.breakText(text, true, 344, null);
                if (count <= 0) break;
                if (count < text.length()) {
                    int space = text.lastIndexOf(' ', count);
                    if (space > 0) count = space;
                }
                String line = text.substring(0, count).trim();
                if (y > 270 && count < text.length()) line = line + "…";
                canvas.drawText(line, 28, y, paint);
                text = text.substring(count).trim();
                y += 39;
            }
            paint.setTextSize(18);
            paint.setColor(Color.rgb(130, 219, 196));
            canvas.drawText("FROM YOUR PHONE", 28, 370, paint);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            return output.toByteArray();
        } finally { bitmap.recycle(); }
    }
}
