package com.moyue.reader.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.reader.R
import com.moyue.reader.ui.theme.Cinnabar
import com.moyue.reader.ui.theme.Ink
import com.moyue.reader.ui.theme.InkSoft
import com.moyue.reader.ui.theme.PaperDeep
import java.io.File
import kotlin.math.abs

/**
 * 书籍封面。
 *
 * 优先显示 EPUB 提取的封面图片；没有封面时用书名生成"纸书"风格封面：
 * 渐变纸色 + 左侧书脊 + 竖排/多行书名，保证书架不会出现空洞的灰色占位。
 */
@Composable
fun BookCover(
    title: String,
    author: String?,
    coverFile: File?,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(coverFile?.path, coverFile?.lastModified()) {
        coverFile?.takeIf { it.isFile }?.let { decodeSampledBitmap(it, 360, 480) }
    }
    Box(
        modifier = modifier
            .shadow(3.dp, RoundedCornerShape(5.dp))
            .clip(RoundedCornerShape(5.dp))
            .background(PaperDeep),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.shelf_cover, title),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            GeneratedCover(title = title, author = author)
        }
    }
}

@Composable
private fun GeneratedCover(title: String, author: String?) {
    val palettes = remember {
        listOf(
            Color(0xFFE7D4B0) to Color(0xFF4A3828),
            Color(0xFFD8C7A9) to Color(0xFF3A3F35),
            Color(0xFFE3CDBE) to Color(0xFF5B2F24),
            Color(0xFFCFD8C3) to Color(0xFF2C3B2A),
            Color(0xFFDACFC2) to Color(0xFF39332E),
        )
    }
    val pair = palettes[abs(title.hashCode()) % palettes.size]
    val background = pair.first
    val ink = pair.second

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(background.copy(alpha = 0.74f), background),
                ),
            ),
    ) {
        // 书脊
        Box(
            Modifier
                .fillMaxHeight()
                .width(10.dp)
                .background(ink.copy(alpha = 0.16f)),
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = 18.dp, top = 20.dp, end = 14.dp, bottom = 18.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    text = "墨阅",
                    color = ink.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    fontFamily = FontFamily.Serif,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = title,
                    color = ink,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = author?.takeIf { it.isNotBlank() } ?: "佚名",
                    color = ink.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Serif,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .width(26.dp)
                        .height(2.dp)
                        .background(Cinnabar.copy(alpha = 0.7f)),
                )
            }
        }
    }
}

/** 读取封面时按目标尺寸采样，避免大图占用内存。 */
private fun decodeSampledBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > reqWidth * 2 || bounds.outHeight / sample > reqHeight * 2) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    } catch (_: Throwable) {
        null
    }
}
