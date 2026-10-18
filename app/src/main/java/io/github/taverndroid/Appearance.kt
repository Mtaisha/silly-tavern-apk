package io.github.taverndroid

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.io.File
import kotlin.math.roundToInt

/**
 * 开屏图片和桌面图标的自定义。默认开屏图、默认图标在 res/drawable-nodpi（由 design/make_assets.py 生成）。
 *
 * 安卓不允许 app 把自己的启动图标换成任意图片，所以「自定义图标」是在桌面上建一个用这张图的快捷方式。
 */
object Appearance {
    private const val SPLASH_FILE = "appearance/splash.jpg"
    private const val SMALL_ICON_FILE = "appearance/icon_small.png"
    private const val ICON_SHORTCUT = "custom_icon"
    private const val ICON_BG = 0xFF021221.toInt()   // 和 res 里的 icon_bg 一致
    private const val SPLASH_BG = 0xFF011025.toInt() // 和 res 里的 splash_bg 一致

    @Volatile
    private var smallIcon: Pair<Long, Bitmap>? = null

    enum class PinResult { REQUESTED, UPDATED, UNSUPPORTED, FAILED }

    private fun splashFile(context: Context) = File(context.filesDir, SPLASH_FILE)

    /** 自定义开屏图；没设置过是 null，用默认图。 */
    fun customSplash(context: Context): File? = splashFile(context).takeIf { it.isFile && it.length() > 0 }

    /** 把选中的图片缩到长边不超过 2560 像素存起来，下次开屏生效。带透明的图先垫上开屏底色，存成 JPG 才不会发黑。 */
    fun saveSplash(context: Context, uri: Uri) {
        var bitmap = decode(context, uri, 2560)
        if (bitmap.hasAlpha()) {
            val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply {
                drawColor(SPLASH_BG)
                drawBitmap(bitmap, 0f, 0f, null)
            }
            bitmap.recycle()
            bitmap = flat
        }
        val file = splashFile(context)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        try {
            tmp.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) error("图片没能保存") }
        } finally {
            bitmap.recycle()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) error("图片没能保存")
        }
    }

    fun resetSplash(context: Context) {
        splashFile(context).delete()
    }

    /** 按屏幕大小缩小解码，铺满屏幕（裁边）时也不会糊。 */
    fun loadSplash(file: File, screenWidth: Int, screenHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= screenWidth && bounds.outHeight / (sample * 2) >= screenHeight) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * 把图片做成桌面快捷方式用的自适应方图（交给 [pinIcon]），通知栏的剪影小图标也一起换。
     * 在后台线程调用。
     */
    fun saveIcon(context: Context, uri: Uri): Bitmap {
        val src = decode(context, uri, 1024)
        val art: Bitmap
        val small: Bitmap?
        try {
            art = adaptiveBitmap(src)
            small = silhouette(src)
        } finally {
            src.recycle()
        }
        val smallFile = File(context.filesDir, SMALL_ICON_FILE)
        if (small != null) writePng(small, smallFile) else smallFile.delete() // 抠不出图案就用默认的门剪影
        smallIcon = null
        return art
    }

    /** 自定义图标换回默认的门（桌面上建的快捷方式 app 删不掉，要用户长按移除）。 */
    fun resetIcon(context: Context) {
        File(context.filesDir, SMALL_ICON_FILE).delete()
        smallIcon = null
    }

    /**
     * 通知栏的小图标（状态栏和通知左边）。安卓只认单色剪影、自己上色，所以用的是图标转出来的剪影：
     * 设过自定义图标就用它的剪影，没设就用默认门的剪影（res/drawable-xxxhdpi/ic_notification.png）。
     * vivo 等国产系统的通知左边画的是 app 本体的图标（就是 mipmap/ic_launcher），不用这个。
     */
    fun notificationSmallIcon(context: Context): IconCompat {
        val file = File(context.filesDir, SMALL_ICON_FILE)
        if (file.isFile) {
            val stamp = file.lastModified()
            val bitmap = smallIcon?.takeIf { it.first == stamp }?.second
                ?: BitmapFactory.decodeFile(file.path)?.let { decoded ->
                    // 存的是 96 像素；按这台手机的密度缩成 24dp，不然 Android 10~14 的状态栏会把它画得过大
                    val px = (24 * context.resources.displayMetrics.density).roundToInt()
                    Bitmap.createScaledBitmap(decoded, px, px, true)
                }?.also { smallIcon = stamp to it }
            if (bitmap != null) return IconCompat.createWithBitmap(bitmap)
        }
        return IconCompat.createWithResource(context, R.drawable.ic_notification)
    }

    private fun writePng(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) error("图标没能保存") }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) error("图标没能保存")
        }
    }

    /**
     * 把图标图片转成通知栏用的白色剪影，思路和 design/make_assets.py 给默认的门做剪影一样：
     * 带透明的图直接按透明度；不透明的图按「跟四周底色差多少」抠出图案，再补掉图案里的小洞，
     * 只留够大的几块（零星小点丢掉）。背景干净的图效果好；抠不出像样的图案（多半是照片）时返回 null，
     * 通知栏就继续用默认的门剪影。
     */
    private fun silhouette(src: Bitmap): Bitmap? {
        val n = 160
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(square, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        var mask = BooleanArray(n * n)
        val fromAlpha = px.count { Color.alpha(it) < 128 } > n * n / 20
        if (fromAlpha) {
            for (i in px.indices) mask[i] = Color.alpha(px[i]) >= 128
        } else {
            // 四周一圈像素的中位色当底色，跟它差得多的就是图案
            val border = (0 until n).flatMap { listOf(px[it], px[(n - 1) * n + it], px[it * n], px[it * n + n - 1]) }
            fun median(channel: (Int) -> Int) = border.map(channel).sorted()[border.size / 2]
            val r0 = median(Color::red)
            val g0 = median(Color::green)
            val b0 = median(Color::blue)
            for (i in px.indices) {
                val dr = Color.red(px[i]) - r0
                val dg = Color.green(px[i]) - g0
                val db = Color.blue(px[i]) - b0
                mask[i] = dr * dr + dg * dg + db * db > 40 * 40
            }
        }
        val raw = mask.copyOf()
        mask = morph(morph(mask, n, 4, dilate = true), n, 4, dilate = false) // 闭合：补掉图案里的小洞
        // 只留够大的几块：零星的亮点、角落的签名不算（免得把外框撑大、主体缩到角落），
        // 分成几部分的标志（比如两个分开的字母）每块都不太小，就都留着
        mask = mainRegions(mask, n) ?: return null
        val area = mask.count { it }
        var left = n; var top = n; var right = -1; var bottom = -1
        for (y in 0 until n) for (x in 0 until n) if (mask[y * n + x]) {
            left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
        }
        // 贴没贴边按补洞之前的像素算：补洞会把离边很近的图案「粘」到边上
        val touches = BooleanArray(4)
        for (i in mask.indices) if (mask[i] && raw[i]) {
            val x = i % n
            val y = i / n
            if (x == 0) touches[0] = true
            if (y == 0) touches[1] = true
            if (x == n - 1) touches[2] = true
            if (y == n - 1) touches[3] = true
        }
        val edges = touches.count { it }
        // 太小、或者占了大半个画面，抠出来不像样，用默认的门。不透明的图再加一条：
        // 贴着三条边以上多半是照片。带透明背景的标志、抠好的人像本来就适合做剪影，不受这条限制。
        if (area < n * n / 100 || area > n * n * 6 / 10 || (!fromAlpha && edges >= 3)) return null
        val w = right - left + 1
        val h = bottom - top + 1
        val cut = IntArray(w * h) { i -> if (mask[(top + i / w) * n + left + i % w]) Color.WHITE else Color.TRANSPARENT }
        val shape = Bitmap.createBitmap(cut, w, h, Bitmap.Config.ARGB_8888)
        // 96 像素的图标（24dp），四周留 2dp：图案最长边占 80，缩放时边缘自然抗锯齿
        val scale = 80f / maxOf(w, h)
        val sw = maxOf(1, (w * scale).toInt())
        val sh = maxOf(1, (h * scale).toInt())
        val out = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(Bitmap.createScaledBitmap(shape, sw, sh, true), ((96 - sw) / 2).toFloat(), ((96 - sh) / 2).toFloat(), null)
        return out
    }

    /** 上下左右相连的各块里，留下不小于最大那块五分之一的；一块都没有就返回 null。 */
    private fun mainRegions(mask: BooleanArray, n: Int): BooleanArray? {
        val label = IntArray(n * n)
        val queue = IntArray(n * n)
        val sizes = ArrayList<Int>() // 第 k 块（label = k + 1）有多少像素
        var next = 0
        for (start in mask.indices) {
            if (!mask[start] || label[start] != 0) continue
            next++
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = next
            while (head < tail) {
                val i = queue[head++]
                val x = i % n
                val y = i / n
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < n - 1) i + 1 else -1, if (y > 0) i - n else -1, if (y < n - 1) i + n else -1)) {
                    if (j >= 0 && mask[j] && label[j] == 0) {
                        label[j] = next
                        queue[tail++] = j
                    }
                }
            }
            sizes.add(tail)
        }
        val biggest = sizes.maxOrNull() ?: return null
        return BooleanArray(n * n) { label[it] != 0 && sizes[label[it] - 1] * 5 >= biggest }
    }

    /** 方形核的膨胀 / 腐蚀（先横后竖，半径 r）。 */
    private fun morph(src: BooleanArray, n: Int, r: Int, dilate: Boolean): BooleanArray {
        val tmp = BooleanArray(n * n)
        val out = BooleanArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            var v = !dilate
            for (k in maxOf(0, x - r)..minOf(n - 1, x + r)) {
                if (src[y * n + k] == dilate) { v = dilate; break }
            }
            tmp[y * n + x] = v
        }
        for (y in 0 until n) for (x in 0 until n) {
            var v = !dilate
            for (k in maxOf(0, y - r)..minOf(n - 1, y + r)) {
                if (tmp[k * n + x] == dilate) { v = dilate; break }
            }
            out[y * n + x] = v
        }
        return out
    }

    /** 建过这个快捷方式就直接换图，没建过就请桌面添加（会弹系统的添加窗口）。 */
    fun pinIcon(context: Context, art: Bitmap): PinResult {
        val info = ShortcutInfoCompat.Builder(context, ICON_SHORTCUT)
            .setShortLabel(context.getString(R.string.app_name))
            .setIcon(IconCompat.createWithAdaptiveBitmap(art))
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .build()
        val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .any { it.id == ICON_SHORTCUT }
        if (pinned) return if (ShortcutManagerCompat.updateShortcuts(context, listOf(info))) PinResult.UPDATED else PinResult.FAILED
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return PinResult.UNSUPPORTED
        return if (ShortcutManagerCompat.requestPinShortcut(context, info, null)) PinResult.REQUESTED else PinResult.FAILED
    }

    /**
     * 自适应图标画布是 108dp，桌面只露出中间 72dp：整张图（取中间的正方形）放进中间那块，
     * 外圈垫一张放大的同图（桌面做动画时才露出来）。带透明的图就垫纯色底，免得透出两层。
     */
    private fun adaptiveBitmap(src: Bitmap): Bitmap {
        val size = 432
        val inner = size * 72 / 108
        val side = minOf(src.width, src.height)
        val crop = Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (src.hasAlpha()) canvas.drawColor(ICON_BG) else canvas.drawBitmap(src, crop, Rect(0, 0, size, size), paint)
        val offset = (size - inner) / 2
        canvas.drawBitmap(src, crop, Rect(offset, offset, offset + inner, offset + inner), paint)
        return out
    }

    /** 读图并按 maxSide 缩小解码（几千万像素的照片也不会把内存撑爆）；会按照片里记录的方向转正。 */
    private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longest = maxOf(w, h)
            if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest
                decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
            }
        }
}
