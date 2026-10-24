package com.example.aiexercise.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import java.io.File
import java.io.FileOutputStream

/**
 * 内置扫描界面：模仿系统文档扫描的交互（取景 → 连拍 → 手动四角 → 透视矫正 → 多页）。
 *
 * 仅在设备没有 Google 文档扫描器时使用（华为等无 GMS 设备）。
 * 矫正与增强逻辑参考 DocScanner（https://github.com/qicecrem/DocScanner）的四角透视思路，
 * 这里用 Android 原生 Canvas/Matrix 实现，不引入桌面端的 Qt/OpenCV 依赖。
 */
@Composable
fun InAppScanScreen(
    onCancel: () -> Unit,
    onDone: (pages: List<String>) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var capturedPages by remember { mutableStateOf(listOf<String>()) }
    var cropping by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    if (!hasPermission) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("需要相机权限才能扫描")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("授权相机")
            }
            TextButton(onClick = onCancel) { Text("返回") }
        }
        return
    }

    val currentCrop = cropping
    if (currentCrop != null) {
        CropScreen(
            path = currentCrop,
            onCancel = { cropping = null },
            onConfirm = { warpedPath ->
                capturedPages = capturedPages + warpedPath
                cropping = null
            },
        )
        return
    }

    CameraStage(
        pageCount = capturedPages.size,
        busy = busy,
        onCaptured = { path ->
            cropping = path
        },
        onCancel = onCancel,
        onFinish = { onDone(capturedPages) },
        onRetake = {
            capturedPages.lastOrNull()?.let { File(it).delete() }
            capturedPages = capturedPages.dropLast(1)
        },
        setBusy = { busy = it },
    )
}

@Composable
private fun CameraStage(
    pageCount: Int,
    busy: Boolean,
    onCaptured: (String) -> Unit,
    onCancel: () -> Unit,
    onFinish: () -> Unit,
    onRetake: () -> Unit,
    setBusy: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        imageCapture = capture
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("第 ${pageCount + 1} 页 · 内置扫描", color = Color.White)
            Text(
                "对齐纸张边缘，拍完可拖四角矫正；连续拍多页后点「完成」",
                color = Color(0xFFD0D0D0),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pageCount > 0) {
                    TextButton(onClick = onRetake) { Text("删掉上一页", color = Color.White) }
                    Spacer(Modifier.width(8.dp))
                    Text("$pageCount 页", color = Color.White)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onFinish) { Text("完成", color = Color.White) }
                } else {
                    TextButton(onClick = onCancel) { Text("返回", color = Color.White) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val capture = imageCapture ?: return@Button
                    if (busy) return@Button
                    setBusy(true)
                    val file = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.cacheDir,
                        "scan_${System.currentTimeMillis()}.jpg",
                    )
                    capture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(),
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                setBusy(false)
                                onCaptured(file.absolutePath)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                setBusy(false)
                            }
                        },
                    )
                },
                enabled = !busy,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text("拍")
            }

        }
    }
}

/** 拖四角 + 透视矫正（Canvas/Matrix 实现，等价 DocScanner 的 crop/warp 思路）。 */
@Composable
private fun CropScreen(
    path: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = runCatching {
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(path, options)?.let { src -> rotateByExif(path, src) }
        }.getOrNull()
    }
    val image = bitmap
    if (image == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val w = image.width.toFloat()
    val h = image.height.toFloat()
    val inset = 0.04f
    var corners by remember(image) {
        mutableStateOf(
            listOf(
                Offset(w * inset, h * inset),
                Offset(w * (1 - inset), h * inset),
                Offset(w * (1 - inset), h * (1 - inset)),
                Offset(w * inset, h * (1 - inset)),
            ),
        )
    }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(image) {
                        detectDragGestures { change, drag ->
                            val scale = minOf(size.width / w, size.height / h)
                            val offsetX = (size.width - w * scale) / 2
                            val offsetY = (size.height - h * scale) / 2
                            val point = Offset(
                                ((change.position.x - offsetX) / scale).coerceIn(0f, w),
                                ((change.position.y - offsetY) / scale).coerceIn(0f, h),
                            )
                            var nearest = -1
                            var best = 120f
                            corners.forEachIndexed { index, corner ->
                                val distance = (corner - point).getDistance()
                                if (distance < best) {
                                    best = distance
                                    nearest = index
                                }
                            }
                            if (nearest >= 0) {
                                val updated = corners.toMutableList()
                                updated[nearest] = point
                                corners = updated
                            }
                        }
                    },
            ) {
                val scale = minOf(size.width / w, size.height / h)
                val offsetX = (size.width - w * scale) / 2
                val offsetY = (size.height - h * scale) / 2
                val mapped = corners.map { Offset(it.x * scale + offsetX, it.y * scale + offsetY) }
                for (i in mapped.indices) {
                    drawLine(
                        color = Color(0xFF6EA8FF),
                        start = mapped[i],
                        end = mapped[(i + 1) % mapped.size],
                        strokeWidth = 4f,
                    )
                }
                mapped.forEach { point ->
                    drawCircle(Color.White, radius = 16f, center = point)
                    drawCircle(Color(0xFF6EA8FF), radius = 9f, center = point)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = onCancel) { Text("重拍") }
            Button(
                onClick = {
                    if (busy) return@Button
                    busy = true
                    val warped = runCatching {
                        val out = warpAndEnhance(image, corners)
                        val file = File(
                            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.cacheDir,
                            "scan_warp_${System.currentTimeMillis()}.jpg",
                        )
                        FileOutputStream(file).use { out.bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                        file.absolutePath
                    }.getOrNull()
                    busy = false
                    if (warped != null) onConfirm(warped)
                },
                enabled = !busy,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("确认并加入扫描")
            }
        }
    }
}

private data class Warped(val bitmap: Bitmap)

/** 四角透视 → 拉直 → 轻微增强对比度。 */
private fun warpAndEnhance(src: Bitmap, corners: List<Offset>): Warped {
    val topLeft = corners[0]
    val topRight = corners[1]
    val bottomRight = corners[2]
    val bottomLeft = corners[3]
    val width = maxOf(
        (topRight - topLeft).getDistance(),
        (bottomRight - bottomLeft).getDistance(),
    ).toInt().coerceAtLeast(64)
    val height = maxOf(
        (bottomLeft - topLeft).getDistance(),
        (bottomRight - topRight).getDistance(),
    ).toInt().coerceAtLeast(64)
    val source = floatArrayOf(
        topLeft.x, topLeft.y,
        topRight.x, topRight.y,
        bottomRight.x, bottomRight.y,
        bottomLeft.x, bottomLeft.y,
    )
    val target = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
    val matrix = Matrix().apply { setPolyToPoly(source, 0, target, 0, 4) }
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(output).drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    return Warped(boostContrast(output))
}

private fun boostContrast(source: Bitmap): Bitmap {
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    val colorMatrix = android.graphics.ColorMatrix().apply {
        setSaturation(0.05f)
        postConcat(
            android.graphics.ColorMatrix(
                floatArrayOf(
                    1.25f, 0f, 0f, 0f, -20f,
                    0f, 1.25f, 0f, 0f, -20f,
                    0f, 0f, 1.25f, 0f, -20f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
    canvas.drawBitmap(source, 0f, 0f, paint)
    return output
}

private fun rotateByExif(path: String, bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        android.media.ExifInterface(path).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return bitmap
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
