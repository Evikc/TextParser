package com.textparser;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

public final class BitmapUtils {

    private BitmapUtils() {
    }

    public static Bitmap fromImageProxy(@NonNull ImageProxy imageProxy) {
        Bitmap bitmap = decodeImageProxy(imageProxy);
        if (bitmap == null) {
            return null;
        }

        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        if (rotation == 0) {
            return bitmap;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
    }

    private static Bitmap decodeImageProxy(ImageProxy imageProxy) {
        if (imageProxy.getFormat() == android.graphics.ImageFormat.JPEG) {
            return decodeJpeg(imageProxy);
        }
        return decodeRgba(imageProxy);
    }

    private static Bitmap decodeJpeg(ImageProxy imageProxy) {
        ByteBuffer buffer = imageProxy.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private static Bitmap decodeRgba(ImageProxy imageProxy) {
        ImageProxy.PlaneProxy plane = imageProxy.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (pixelStride == 4 && rowStride == width * 4) {
            buffer.rewind();
            bitmap.copyPixelsFromBuffer(buffer);
            return bitmap;
        }

        int[] pixels = new int[width * height];
        buffer.rewind();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = y * rowStride + x * pixelStride;
                int r = buffer.get(offset) & 0xFF;
                int g = buffer.get(offset + 1) & 0xFF;
                int b = buffer.get(offset + 2) & 0xFF;
                int a = buffer.get(offset + 3) & 0xFF;
                pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }
}
