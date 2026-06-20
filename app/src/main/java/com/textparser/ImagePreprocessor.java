package com.textparser;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

public final class ImagePreprocessor {

    private static final int MIN_WIDTH = 1800;
    private static final float CONTRAST = 1.25f;

    private ImagePreprocessor() {
    }

    public static Bitmap prepareForOcr(Bitmap source) {
        Bitmap scaled = scaleUp(source);
        Bitmap gray = toGrayscale(scaled);
        if (scaled != source) {
            scaled.recycle();
        }
        Bitmap contrasted = enhanceContrast(gray);
        gray.recycle();
        return contrasted;
    }

    private static Bitmap scaleUp(Bitmap source) {
        if (source.getWidth() >= MIN_WIDTH) {
            return source.copy(Bitmap.Config.ARGB_8888, false);
        }

        float scale = (float) MIN_WIDTH / source.getWidth();
        int newHeight = Math.round(source.getHeight() * scale);
        return Bitmap.createScaledBitmap(source, MIN_WIDTH, newHeight, true);
    }

    private static Bitmap toGrayscale(Bitmap source) {
        Bitmap result = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(0f);
        paint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
        canvas.drawBitmap(source, 0f, 0f, paint);
        return result;
    }

    private static Bitmap enhanceContrast(Bitmap source) {
        Bitmap result = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        float translate = (-0.5f * CONTRAST + 0.5f) * 255f;
        ColorMatrix contrastMatrix = new ColorMatrix(new float[]{
                CONTRAST, 0, 0, 0, translate,
                0, CONTRAST, 0, 0, translate,
                0, 0, CONTRAST, 0, translate,
                0, 0, 0, 1, 0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(contrastMatrix));
        canvas.drawBitmap(source, 0f, 0f, paint);
        return result;
    }
}
