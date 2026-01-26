/*
 * Copyright 2025 DroidOCR Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tenshi18.droidocr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tenshi18.droidocr.ui.theme.DroidOCRTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class MainActivity : ComponentActivity() {
    
    private val ppocrRec = PPOCRv5Rec()
    private lateinit var languageManager: LanguageManager
    private var isModelLoaded by mutableStateOf(false)
    
    override fun attachBaseContext(newBase: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            super.attachBaseContext(newBase)
        } else {
            super.attachBaseContext(LocaleHelper.attachBaseContext(newBase))
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        languageManager = LanguageManager(this)
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_CODE_STORAGE
            )
        }
        
        loadModel()
        
        setContent {
            DroidOCRTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OCRScreen(
                        ppocrRec = ppocrRec,
                        isModelLoaded = isModelLoaded,
                        languageManager = languageManager,
                        onLanguageChanged = { language ->
                            switchLanguage(language)
                        }
                    )
                }
            }
        }
    }
    
    private fun loadModel() {
        try {
            val config = languageManager.getCurrentLanguageConfig()
            isModelLoaded = ppocrRec.loadModel(
                assetManager = assets,
                detParamPath = "PP_OCRv5_mobile_det.ncnn.param",
                detBinPath = "PP_OCRv5_mobile_det.ncnn.bin",
                recParamPath = config.recParamPath,
                recBinPath = config.recBinPath,
                dictPath = config.dictPath,
                useGpu = false
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to load model", e)
        }
    }

    
    private fun switchLanguage(language: OcrLanguage) {
        languageManager.setLanguage(language)
        val config = language.config
        try {
            isModelLoaded = ppocrRec.switchLanguage(
                assetManager = assets,
                detParamPath = "PP_OCRv5_mobile_det.ncnn.param",
                detBinPath = "PP_OCRv5_mobile_det.ncnn.bin",
                recParamPath = config.recParamPath,
                recBinPath = config.recBinPath,
                dictPath = config.dictPath,
                useGpu = false
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to switch language", e)
            isModelLoaded = false
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        ppocrRec.release()
    }
    
    companion object {
        private const val REQUEST_CODE_STORAGE = 100
    }
}

@Composable
fun InteractiveImageWithText(
    bitmap: Bitmap,
    textRegions: Array<TextRegion>,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false
) {
    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                imageSize = coordinates.size
            }
        ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.image_with_text),
            modifier = if (isFullscreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
            },
            contentScale = ContentScale.Fit
        )
        
        if (imageSize.width > 0 && imageSize.height > 0) {
            val scaleX = imageSize.width.toFloat() / bitmap.width
            val scaleY = imageSize.height.toFloat() / bitmap.height
            val scale = minOf(scaleX, scaleY)
            
            val offsetX = (imageSize.width - bitmap.width * scale) / 2f
            val offsetY = (imageSize.height - bitmap.height * scale) / 2f
            
            val textMeasurer = rememberTextMeasurer()
            
            val avgHeight = if (textRegions.isNotEmpty()) {
                textRegions.map { 
                    it.corners.maxOf { p -> p.y } - it.corners.minOf { p -> p.y }
                }.average() * scale
            } else {
                0.0
            }
            val lineThreshold = (avgHeight * 0.6f).toFloat()
                
            val sortedRegions = textRegions.sortedWith(compareBy(
                { it.corners.minOf { p -> p.y } },
                { it.corners.minOf { p -> p.x } }
            ))
            
            SelectionContainer {
                Box(modifier = Modifier.fillMaxSize()) {
                    sortedRegions.forEachIndexed { index, region ->
                        val minX = region.corners.minOf { it.x } * scale + offsetX
                        val minY = region.corners.minOf { it.y } * scale + offsetY
                        val maxX = region.corners.maxOf { it.x } * scale + offsetX
                        val maxY = region.corners.maxOf { it.y } * scale + offsetY
                        
                        val width = maxX - minX
                        val height = maxY - minY
                        
                        val fontSize = with(density) { (height * 0.7f).toSp() }
                        
                        val textLayoutResult = textMeasurer.measure(
                            text = region.text,
                            style = TextStyle(fontSize = fontSize)
                        )
                        val naturalWidth = textLayoutResult.size.width
                        
                        val letterSpacing = if (naturalWidth > 0 && region.text.length > 1) {
                            val extraSpace = width - naturalWidth
                            val spacingPerChar = extraSpace / (region.text.length - 1)
                            with(density) { spacingPerChar.toSp() }
                        } else {
                            0.sp
                        }
                        
                        Text(
                            text = region.text,
                            modifier = Modifier
                                .offset(
                                    x = with(density) { minX.toDp() },
                                    y = with(density) { minY.toDp() }
                                )
                                .width(with(density) { width.toDp() })
                                .height(with(density) { height.toDp() }),
                            style = TextStyle(
                                fontSize = fontSize,
                                color = Color.Transparent,
                                lineHeight = with(density) { height.toSp() },
                                letterSpacing = letterSpacing
                            ),
                            maxLines = 1
                        )
                        
                        if (index < sortedRegions.size - 1) {
                            val nextRegion = sortedRegions[index + 1]
                            val currentCenterY = (minY + maxY) / 2f
                            val nextMinY = nextRegion.corners.minOf { it.y } * scale + offsetY
                            val nextMaxY = nextRegion.corners.maxOf { it.y } * scale + offsetY
                            val nextCenterY = (nextMinY + nextMaxY) / 2f
                            
                            val separatorText = if (kotlin.math.abs(currentCenterY - nextCenterY) < lineThreshold) {
                                " "
                            } else {
                                "\n"
                            }
                            
                            val separatorX = maxX
                            val separatorY = (currentCenterY + nextCenterY) / 2f
                            
                            Text(
                                text = separatorText,
                                modifier = Modifier
                                    .offset(
                                        x = with(density) { separatorX.toDp() },
                                        y = with(density) { separatorY.toDp() }
                                    ),
                                style = TextStyle(
                                    fontSize = fontSize,
                                    color = Color.Transparent
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OCRScreen(
    ppocrRec: PPOCRv5Rec,
    isModelLoaded: Boolean,
    languageManager: LanguageManager,
    onLanguageChanged: (OcrLanguage) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var recognizedText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var textRegions by remember { mutableStateOf<Array<TextRegion>>(emptyArray()) }
    var showFullscreenImage by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var currentLanguage by remember { mutableStateOf(languageManager.getCurrentLanguage()) }

    // Function to process image from Uri
    fun processImageFromUri(uri: Uri) {
        scope.launch {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap != null) {
                    selectedImageBitmap = bitmap

                    if (isModelLoaded) {
                        isProcessing = true
                        val regions = withContext(Dispatchers.Default) {
                            ppocrRec.detectAndRecognizeWithBoxes(bitmap)
                        }

                        val sortedRegions = regions.sortedWith(compareBy(
                            { it.corners.minOf { p -> p.y } },
                            { it.corners.minOf { p -> p.x } }
                        )).toTypedArray()

                        textRegions = sortedRegions
                        recognizedText = sortedRegions.joinToString("\n") { it.text }

                        isProcessing = false
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to process image", e)
                recognizedText = context.resources.getString(R.string.error_prefix, e.message ?: "")
                isProcessing = false
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processImageFromUri(it) }
    }

    val scrollState = rememberScrollState()

    @Composable
    fun ColumnContent() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium
            )

            Button(
                onClick = { showLanguageDialog = true }
            ) {
                Text(
                    text = currentLanguage.displayName,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        if (showLanguageDialog) {
            LanguageSelectionDialog(
                currentLanguage = currentLanguage,
                onLanguageSelected = { language ->
                    currentLanguage = language
                    showLanguageDialog = false
                    scope.launch(Dispatchers.IO) {
                        onLanguageChanged(language)
                    }
                },
                onDismiss = { showLanguageDialog = false }
            )
        }

        if (selectedImageBitmap == null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(
                            R.string.model_status,
                            if (isModelLoaded) stringResource(R.string.model_loaded)
                            else stringResource(R.string.model_not_loaded)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isModelLoaded)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Button(
            onClick = { imagePickerLauncher.launch("image/*") },
            enabled = isModelLoaded && !isProcessing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(stringResource(R.string.select_image))
        }

        selectedImageBitmap?.let { bitmap ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.selected_image),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                        .padding(8.dp)
                        .clickable { showFullscreenImage = true },
                    contentScale = ContentScale.Fit
                )
            }
        }

        if (isProcessing) {
            CircularProgressIndicator(
                modifier = Modifier.padding(16.dp)
            )
            Text(stringResource(R.string.processing))
        }

        if (recognizedText.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.recognized_text),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    SelectionContainer {
                        Text(
                            text = recognizedText,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

        if (selectedImageBitmap == null && recognizedText.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.instructions),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = stringResource(R.string.instruction_steps),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ColumnContent()
    }

    if (showFullscreenImage && selectedImageBitmap != null) {
        FullscreenImageDialog(
            bitmap = selectedImageBitmap!!,
            textRegions = textRegions,
            onDismiss = { showFullscreenImage = false }
        )
    }
}

@Composable
fun FullscreenImageDialog(
    bitmap: Bitmap,
    textRegions: Array<TextRegion>,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        if (textRegions.isNotEmpty()) {
            InteractiveImageWithText(
                bitmap = bitmap,
                textRegions = textRegions,
                modifier = Modifier.fillMaxSize(),
                isFullscreen = true
            )
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.fullscreen_image),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
fun LanguageSelectionDialog(
    currentLanguage: OcrLanguage,
    onLanguageSelected: (OcrLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.select_language))
        },
        text = {
            Column {
                OcrLanguage.entries.forEach { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLanguageSelected(language) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = language == currentLanguage,
                            onClick = { onLanguageSelected(language) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = language.displayName,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}