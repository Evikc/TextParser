package com.textparser;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private PreviewView previewView;
    private ImageView frozenPreview;
    private TextView recognizedTextView;
    private TextView statusTextView;
    private MaterialButton copyButton;
    private MaterialButton clearButton;
    private FloatingActionButton captureButton;
    private ProgressBar progressBar;
    private LinearLayout galleryButtonContainer;

    private ExecutorService cameraExecutor;
    private OcrEngine ocrEngine;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private String lastRecognizedText = "";
    private boolean isCapturing;
    private Bitmap frozenBitmap;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    initOcrAndCamera();
                } else {
                    showStatus(getString(R.string.camera_permission_denied));
                }
            });

    private final ActivityResultLauncher<String> requestGalleryPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    selectFromGallery();
                } else {
                    Toast.makeText(this, R.string.gallery_permission_denied, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        frozenPreview = findViewById(R.id.frozenPreview);
        recognizedTextView = findViewById(R.id.recognizedText);
        statusTextView = findViewById(R.id.statusText);
        copyButton = findViewById(R.id.copyButton);
        clearButton = findViewById(R.id.clearButton);
        captureButton = findViewById(R.id.captureButton);
        progressBar = findViewById(R.id.progressBar);
        galleryButtonContainer = findViewById(R.id.galleryButtonContainer);

        cameraExecutor = Executors.newSingleThreadExecutor();
        ocrEngine = new OcrEngine();

        copyButton.setOnClickListener(v -> copyTextToClipboard());
        clearButton.setOnClickListener(v -> clearRecognizedText());
        captureButton.setOnClickListener(v -> captureSnapshot());
        galleryButtonContainer.setOnClickListener(v -> selectFromGallery());

        if (hasCameraPermission()) {
            initOcrAndCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void initOcrAndCamera() {
        setLoading(true);
        showStatus(getString(R.string.ocr_initializing));

        cameraExecutor.execute(() -> {
            boolean ready = ocrEngine.init(MainActivity.this);
            runOnUiThread(() -> {
                setLoading(false);
                if (ready) {
                    startCamera();
                } else {
                    showStatus(getString(R.string.ocr_init_error));
                    captureButton.setEnabled(false);
                    galleryButtonContainer.setEnabled(false);
                }
            });
        });
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
                showStatus(getString(R.string.point_camera_at_text));
            } catch (Exception e) {
                showStatus(getString(R.string.camera_start_error));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) {
            return;
        }

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build();

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageCapture
        );
    }

    private void pauseCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    private void resumeCamera() {
        clearFrozenPreview();
        bindCameraUseCases();
    }

    private void showFrozenPreview(@NonNull Bitmap bitmap) {
        clearFrozenPreview();
        frozenBitmap = bitmap;
        frozenPreview.setImageBitmap(frozenBitmap);
        frozenPreview.setVisibility(View.VISIBLE);
    }

    private void clearFrozenPreview() {
        frozenPreview.setVisibility(View.GONE);
        frozenPreview.setImageDrawable(null);
        if (frozenBitmap != null) {
            frozenBitmap.recycle();
            frozenBitmap = null;
        }
    }

    private void captureSnapshot() {
        if (imageCapture == null || isCapturing) {
            return;
        }

        isCapturing = true;
        captureButton.setEnabled(false);
        setLoading(true);
        showStatus(getString(R.string.processing_snapshot));

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                Bitmap bitmap = BitmapUtils.fromImageProxy(imageProxy);
                imageProxy.close();

                if (bitmap == null) {
                    runOnUiThread(() -> finishCapture(getString(R.string.snapshot_error), true));
                    return;
                }

                Bitmap snapshot = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                bitmap.recycle();

                runOnUiThread(() -> {
                    pauseCamera();
                    showFrozenPreview(snapshot);
                });

                cameraExecutor.execute(() -> {
                    try {
                        String text = ocrEngine.recognize(snapshot);
                        runOnUiThread(() -> {
                            finishCapture(null, true);
                            updateRecognizedText(text);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> finishCapture(getString(R.string.recognition_error), true));
                    }
                });
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> finishCapture(getString(R.string.snapshot_error), false));
            }
        });
    }

    private void finishCapture(String errorMessage, boolean resumeCameraAfter) {
        setLoading(false);
        isCapturing = false;
        captureButton.setEnabled(true);
        galleryButtonContainer.setEnabled(true);

        if (resumeCameraAfter) {
            resumeCamera();
        } else {
            clearFrozenPreview();
            if (cameraProvider != null) {
                bindCameraUseCases();
            }
        }

        if (errorMessage != null) {
            showStatus(errorMessage);
        }
    }

    private void updateRecognizedText(String text) {
        if (text.isEmpty()) {
            showStatus(getString(R.string.no_text_on_snapshot));
            return;
        }

        lastRecognizedText = text;
        recognizedTextView.setText(text);
        statusTextView.setVisibility(View.GONE);
        copyButton.setEnabled(true);
        Toast.makeText(this, R.string.snapshot_done, Toast.LENGTH_SHORT).show();
    }

    private void clearRecognizedText() {
        lastRecognizedText = "";
        recognizedTextView.setText(R.string.text_placeholder);
        copyButton.setEnabled(false);
        showStatus(getString(R.string.point_camera_at_text));
    }

    private void selectFromGallery() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                requestGalleryPermissionLauncher.launch(android.Manifest.permission.READ_MEDIA_IMAGES);
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestGalleryPermissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE);
                return;
            }
        }
        
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        galleryLauncher.launch(intent);
    }

    private final ActivityResultLauncher<Intent> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    if (imageUri != null) {
                        processGalleryImage(imageUri);
                    }
                }
            });

    private void processGalleryImage(Uri imageUri) {
        if (isCapturing) {
            return;
        }

        isCapturing = true;
        captureButton.setEnabled(false);
        galleryButtonContainer.setEnabled(false);
        setLoading(true);
        showStatus(getString(R.string.processing_image));

        cameraExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
            } catch (IOException e) {
                runOnUiThread(() -> finishCapture(getString(R.string.image_load_error), true));
                return;
            }

            if (bitmap == null) {
                runOnUiThread(() -> finishCapture(getString(R.string.image_load_error), true));
                return;
            }

            Bitmap snapshot = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            bitmap.recycle();

            runOnUiThread(() -> {
                pauseCamera();
                showFrozenPreview(snapshot);
            });

            cameraExecutor.execute(() -> {
                try {
                    String text = ocrEngine.recognize(snapshot);
                    runOnUiThread(() -> {
                        finishCapture(null, true);
                        updateRecognizedText(text);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> finishCapture(getString(R.string.recognition_error), true));
                }
            });
        });
    }

    private void copyTextToClipboard() {
        if (lastRecognizedText.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_copy, Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("recognized_text", lastRecognizedText);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, R.string.text_copied, Toast.LENGTH_SHORT).show();
    }

    private void showStatus(String message) {
        statusTextView.setText(message);
        statusTextView.setVisibility(View.VISIBLE);
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearFrozenPreview();
        if (ocrEngine != null) {
            ocrEngine.close();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
