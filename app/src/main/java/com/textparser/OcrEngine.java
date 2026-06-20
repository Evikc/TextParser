package com.textparser;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class OcrEngine {

    private static final String TESSDATA_DIR = "tessdata";
    private static final String LANGUAGE = "rus";
    private static final String PREFS_NAME = "ocr_prefs";
    private static final String PREF_MODEL_VERSION = "model_version";
    private static final int MODEL_VERSION = 4;
    private static final long MIN_MODEL_SIZE_BYTES = 1_000_000L;

    private final TessBaseAPI tessBaseApi;
    private boolean initialized;

    public OcrEngine() {
        tessBaseApi = new TessBaseAPI();
    }

    public synchronized boolean init(Context context) {
        if (initialized) {
            return true;
        }

        Context appContext = context.getApplicationContext();
        String dataPath = prepareTessData(appContext);
        if (dataPath == null) {
            resetModelState(appContext);
            return false;
        }

        initialized = tessBaseApi.init(dataPath, LANGUAGE, TessBaseAPI.OEM_LSTM_ONLY);
        if (initialized) {
            tessBaseApi.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
            markModelReady(appContext);
        } else {
            resetModelState(appContext);
        }

        return initialized;
    }

    public synchronized String recognize(Bitmap bitmap) {
        if (!initialized || bitmap == null) {
            return "";
        }

        Bitmap prepared = ImagePreprocessor.prepareForOcr(bitmap);
        try {
            tessBaseApi.setImage(prepared);
            String text = extractConfidentText();
            tessBaseApi.clear();
            return cleanText(text);
        } finally {
            if (prepared != bitmap) {
                prepared.recycle();
            }
        }
    }

    public synchronized void close() {
        if (initialized) {
            tessBaseApi.recycle();
            initialized = false;
        }
    }

    private String extractConfidentText() {
        tessBaseApi.getUTF8Text();
        String raw = tessBaseApi.getConfidentText(
                65,
                TessBaseAPI.PageIteratorLevel.RIL_WORD
        );

        StringBuilder output = new StringBuilder();
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (TextFilter.isUsefulLine(trimmed, 65f)) {
                if (output.length() > 0) {
                    output.append('\n');
                }
                output.append(trimmed);
            }
        }
        return output.toString();
    }

    private String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return text
                .replace("\r\n", "\n")
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String prepareTessData(Context context) {
        File tessDataFolder = new File(context.getFilesDir(), TESSDATA_DIR);
        if (!tessDataFolder.exists() && !tessDataFolder.mkdirs()) {
            return null;
        }

        File trainedDataFile = new File(tessDataFolder, LANGUAGE + ".traineddata");
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean needsCopy = !isModelValid(trainedDataFile)
                || prefs.getInt(PREF_MODEL_VERSION, 0) != MODEL_VERSION;

        if (needsCopy && !copyAsset(context.getAssets(), trainedDataFile)) {
            return null;
        }

        if (!isModelValid(trainedDataFile)) {
            return null;
        }

        return context.getFilesDir().getAbsolutePath();
    }

    private boolean isModelValid(File trainedDataFile) {
        return trainedDataFile.exists() && trainedDataFile.length() >= MIN_MODEL_SIZE_BYTES;
    }

    private void markModelReady(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(PREF_MODEL_VERSION, MODEL_VERSION)
                .apply();
    }

    private void resetModelState(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(PREF_MODEL_VERSION).apply();

        File trainedDataFile = new File(
                new File(context.getFilesDir(), TESSDATA_DIR),
                LANGUAGE + ".traineddata"
        );
        if (trainedDataFile.exists() && !trainedDataFile.delete()) {
            trainedDataFile.deleteOnExit();
        }
    }

    private boolean copyAsset(AssetManager assets, File destination) {
        if (destination.exists() && !destination.delete()) {
            return false;
        }

        try (InputStream input = assets.open(TESSDATA_DIR + "/" + LANGUAGE + ".traineddata");
             OutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
            return isModelValid(destination);
        } catch (IOException e) {
            if (destination.exists()) {
                destination.delete();
            }
            return false;
        }
    }
}
