package com.jujinkim.frequaw

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import com.jujinkim.frequaw.data.FrequawDataHelper
import com.jujinkim.frequaw.data.FrequawWidgetSettingData
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.util.*
import kotlin.math.min
import kotlin.math.roundToInt

class IconHelper(context: Context, private val widgetId: Int) {
    private val widgetSetting: FrequawWidgetSettingData
    get() = FrequawDataHelper.loadWidgetSetting(widgetId)

    private var layerSize: Int
    private var iconSize: Int

    private var packageManager: PackageManager = context.packageManager
    private var cornerRadius: Float

    private val isForceApplyShape = widgetSetting.isForceIconShapeClip
    private val isThemeUnmatchedWithIconPack = widgetSetting.isThemeUnmatchedWithIconPack

    private val iconPack: IconPack

    init {
        layerSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, APP_ICON_SIZE_DP, context.resources.displayMetrics
        ).roundToInt()
        iconSize =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                (layerSize / (1 + 2 * AdaptiveIconDrawable.getExtraInsetFraction())).toInt()
            else
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 72f, context.resources.displayMetrics
                ).roundToInt()
        cornerRadius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, SQUARE_CORNER_RADIUS_DP, context.resources.displayMetrics
        )

        iconPack = IconPackCache.get(widgetSetting.appIconPackPackage, packageManager)
    }

    @Throws(NameNotFoundException::class)
    fun getAppIcon(packageName: String, type: AppIconStyle = AppIconStyle.System): Bitmap {
        try {
            if (iconPack.isReady) {
                val matched = iconPack.getAppIconDrawable(packageName)
                if (matched != null) return getClippedIcon(matched, type)

                val stockIcon = packageManager.getApplicationIcon(packageName)
                return if (isThemeUnmatchedWithIconPack) {
                    // wrap the stock icon in the pack's back plate
                    getClippedIcon(iconPack.applyIconPackTheme(stockIcon), type)
                } else {
                    // No pack background, but still normalize the logo to fill the tile so it
                    // matches the size of the pack's own icons (which are full-bleed art).
                    filledIcon(stockIcon, type)
                }
            }

            return getClippedIcon(packageManager.getApplicationIcon(packageName), type)

        } catch (e: NameNotFoundException) {
            e.printStackTrace()
            throw NameNotFoundException()
        }
    }

    /**
     * Builds an icon whose logo fills the tile: takes the drawable's foreground content, trims its
     * transparent margin, and scales it to cover the canvas, centered. Used for apps an icon pack
     * has no entry for, so they are the same visual size as the pack's full-bleed icons instead of
     * a small safe-zone logo. The selected shape is applied unless the System style is selected.
     */
    private fun filledIcon(drawable: Drawable, iconType: AppIconStyle): Bitmap {
        val filled = drawable.toForegroundBitmap().coverFilled()
        return if (iconType != AppIconStyle.System) getBitmapClipped(filled, iconType) else filled
    }

    fun getClippedIcon(baseIcon: Drawable, iconType: AppIconStyle) : Bitmap {
        val isAdaptive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && baseIcon is AdaptiveIconDrawable

        // Build a full-bleed bitmap: adaptive icons are cropped to their safe zone (108dp -> 72dp)
        // the way a launcher does; everything else is rasterized as-is. Both are then content-filled
        // so a padded icon (e.g. a matched pack icon with transparent margins) is the same visual
        // size as the others. coverFilled is a no-op for already full-bleed art.
        val bitmap = if (isAdaptive) {
            renderAdaptiveBitmap(baseIcon as AdaptiveIconDrawable).coverFilled()
        } else {
            baseIcon.toBitmap(config = Bitmap.Config.ARGB_8888).coverFilled()
        }

        // Clip adaptive icons (arbitrary outlines) to the chosen shape always; clip legacy / pack
        // icons only when the user forces it. The System style is never clipped.
        val clipToShape = iconType != AppIconStyle.System && (isAdaptive || isForceApplyShape)
        return if (clipToShape) getBitmapClipped(bitmap, iconType) else bitmap
    }

    /**
     * Rasterizes an [AdaptiveIconDrawable] cropped to its safe zone: both layers are drawn at the
     * full 108dp [layerSize] but centered inside the smaller [iconSize] (72dp) canvas, so the 18%
     * bleed margin is cropped away and the visible content fills the bitmap, matching launcher
     * presentation.
     */
    private fun renderAdaptiveBitmap(baseIcon: AdaptiveIconDrawable): Bitmap {
        val drr = arrayOfNulls<Drawable>(2)
        drr[0] = baseIcon.background
        drr[1] = baseIcon.foreground
        val layerDrawable = LayerDrawable(drr).apply {
            setLayerGravity(0, Gravity.CENTER)
            setLayerGravity(1, Gravity.CENTER)
            setLayerSize(0, layerSize, layerSize)
            setLayerSize(1, layerSize, layerSize)
        }
        val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        layerDrawable.setBounds(0, 0, iconSize, iconSize)
        layerDrawable.draw(Canvas(bitmap))
        return bitmap
    }

    // Clips [bitmap] to the shape for [iconType]. Callers pass a content-filled bitmap (see
    // coverFilled), so the shape is fully covered with no flat/empty edge from a transparent margin.
    private fun getBitmapClipped(bitmap: Bitmap, iconType: AppIconStyle) : Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { density = bitmap.density }
        val path = when (iconType) {
            AppIconStyle.Circle-> circlePath(width, height)
            AppIconStyle.Square -> squarePath(width, height)
            AppIconStyle.Squircle -> squirclePath(width, height)
            AppIconStyle.RoundedSquare -> roundSquarePath(width, height)
            else -> circlePath(width, height)
        }

        val canvas = Canvas(outputBitmap)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        canvas.restore()
        // Do NOT recycle `bitmap`: Drawable.toBitmap() can return a BitmapDrawable's
        // shared backing bitmap (cached app icon). Recycling it crashes later renders
        // with "Canvas: trying to use a recycled bitmap". Let GC reclaim it.
        return outputBitmap
    }

    private fun circlePath(width: Int, height: Int) =
        Path().apply {
            addCircle(
                width * .5f,
                height * .5f,
                min(width, height) * .5f,
                Path.Direction.CCW
            )
        }

    private fun squarePath(width: Int, height: Int) =
        Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CCW)
        }

    private fun roundSquarePath(width: Int, height: Int) =
        Path().apply {
            addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), cornerRadius, cornerRadius, Path.Direction.CCW)
        }

    private fun squirclePath(width: Int, height: Int) =
        Path().apply {
            // 4 Cubic bezier curves
            moveTo(0f, height * 0.5f)
            cubicTo(0f, height * SQUIRCLE_FACTOR, width * SQUIRCLE_FACTOR, 0f, width * 0.5f, 0f)  // left top
            cubicTo(width * (1 - SQUIRCLE_FACTOR), 0f, width.toFloat(), height * SQUIRCLE_FACTOR, width.toFloat(), height * 0.5f)   // right top
            cubicTo(width.toFloat(), height * (1 - SQUIRCLE_FACTOR), width * (1 - SQUIRCLE_FACTOR), height.toFloat(), width * 0.5f, height.toFloat()) // right bottom
            cubicTo(width * SQUIRCLE_FACTOR, height.toFloat(), 0f, height * (1 - SQUIRCLE_FACTOR), 0f, height * 0.5f)   // left bottom
            close()
        }

    companion object {
        const val APP_ICON_SIZE_DP = 108f
        const val SQUARE_CORNER_RADIUS_DP = 8f
        const val SQUIRCLE_FACTOR = 0.125f
    }
}

/**
 * Process-wide cache of parsed icon packs. An IconPack parses the whole appfilter.xml
 * (thousands of entries) and decodes back/mask/front bitmaps in its constructor; that work
 * is identical for every widget refresh of the same pack, so build it once per package.
 */
object IconPackCache {
    private var cachedPackage: String? = null
    private var cachedPack: IconPack? = null

    @Synchronized
    fun get(packageName: String, packageManager: PackageManager): IconPack {
        val current = cachedPack
        if (current != null && cachedPackage == packageName) return current
        return IconPack(packageName, packageManager).also {
            cachedPackage = packageName
            cachedPack = it
        }
    }

    @Synchronized
    fun clear() {
        cachedPackage = null
        cachedPack = null
    }
}

/**
 * LRU cache of finished, ready-to-display icon bitmaps keyed by everything that affects the
 * pixels (pack + package + style + force-clip + target size). Avoids decoding/clipping/scaling
 * the same icon on every widget refresh. Evicted bitmaps are NOT recycled: a bitmap may still be
 * referenced by a RemoteViews mid-update, so we let GC reclaim them instead.
 */
object IconBitmapCache {
    private const val MAX_BYTES = 6 * 1024 * 1024 // ~6MB; icon bitmaps are small

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @Synchronized
    fun get(key: String): Bitmap? = cache.get(key)?.takeIf { !it.isRecycled }

    @Synchronized
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }

    @Synchronized
    fun clear() = cache.evictAll()
}

class IconPack(
    val packageName: String,
    val packageManager: PackageManager
) {
    var isReady = false

    private lateinit var iconPackRes: Resources
    private var iconPackResMap = hashMapOf<String, String>()

    private var commonBackImages: MutableList<Bitmap> = mutableListOf()
    private var commonMask: Bitmap? = null
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private var commonFrontImage: Bitmap? = null
    private var commonFactor = 1f

    init {
        if (packageName.isNotBlank()) initIconPack(packageName, packageManager)
    }

    private fun initIconPack(packageName: String,
                             packageManager: PackageManager) {

        // Get XmlPullParser from app filter xml
        val xmlPullParser: XmlPullParser
        try {
            iconPackRes = packageManager.getResourcesForApplication(packageName)
            val appFilterId = iconPackRes.getIdentifier("appfilter", "xml", packageName)
            if (appFilterId > 0) {
                xmlPullParser = iconPackRes.getXml(appFilterId)
            } else {
                val appFilterStream: InputStream = iconPackRes.getAssets().open("appfilter.xml")

                val factory = XmlPullParserFactory.newInstance()
                factory.isNamespaceAware = true
                xmlPullParser = factory.newPullParser()
                xmlPullParser.setInput(appFilterStream, "utf-8")
            }
        } catch (e: IOException) {
            Log.e(FrequawApp.TAG_DEBUG, "Error while load icon pack: no appfilter.xml file")
            e.printStackTrace()
            return
        } catch (e: NameNotFoundException) {
            Log.e(FrequawApp.TAG_DEBUG, "Error while load icon pack: package name's icon pack is not found")
            e.printStackTrace()
            return
        }

        // load icon drawable resource strings
        var eventType = xmlPullParser.eventType
        do {
            if (eventType != XmlPullParser.START_TAG) {
                eventType = xmlPullParser.next()
                continue
            }

            // icon pack bg/fg/mask/factor
            when (xmlPullParser.name.lowercase(Locale.getDefault())) {
                "iconback" -> {
                    for (i in 0 until xmlPullParser.attributeCount) {
                        // iconback lists img1, img2, ... — check each attribute, not index 0
                        if (xmlPullParser.getAttributeName(i).startsWith("img")) {
                            getDrawableFromName(xmlPullParser.getAttributeValue(i))?.let {
                                commonBackImages.add(it.toBitmap(config = Bitmap.Config.ARGB_8888))
                            }
                        }
                    }
                    eventType = xmlPullParser.next()
                    continue
                }
                "iconmask" -> {
                    if (xmlPullParser.attributeCount > 0 &&
                        xmlPullParser.getAttributeName(0).equals("img1")) {
                        val drawableName = xmlPullParser.getAttributeValue(0)
                        commonMask = getDrawableFromName(drawableName)?.toBitmap(config = Bitmap.Config.ARGB_8888)
                    }
                    eventType = xmlPullParser.next()
                    continue
                }
                "iconupon" -> {
                    if (xmlPullParser.attributeCount > 0 &&
                        xmlPullParser.getAttributeName(0).equals("img1")) {
                        val drawableName = xmlPullParser.getAttributeValue(0)
                        commonFrontImage = getDrawableFromName(drawableName)?.toBitmap(config = Bitmap.Config.ARGB_8888)
                    }
                    eventType = xmlPullParser.next()
                    continue
                }
                "scale" -> {
                    if (xmlPullParser.attributeCount > 0 &&
                        xmlPullParser.getAttributeName(0).equals("factor")) {
                        commonFactor = xmlPullParser.getAttributeValue(0).toFloat()
                    }
                    eventType = xmlPullParser.next()
                    continue
                }
            }

            if (xmlPullParser.name.lowercase(Locale.getDefault()) != "item") {
                eventType = xmlPullParser.next()
                continue
            }

            val componentStr = xmlPullParser.getAttributeValue(null, "component")
            val drawableStr = xmlPullParser.getAttributeValue(null, "drawable")
            if (componentStr.isNullOrBlank() || drawableStr.isNullOrBlank()) {
                eventType = xmlPullParser.next()
                continue
            }

            iconPackResMap[componentStr] = drawableStr
            Log.d(FrequawApp.TAG_DEBUG, "icon pack loaded - $componentStr + $drawableStr")

            eventType = xmlPullParser.next()
        } while (eventType != XmlPullParser.END_DOCUMENT)

        isReady = true
    }

    fun getAppIconDrawable(packageName: String) : Drawable? {
        if (!isReady) return null

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val componentName = (launchIntent?.component ?: return null).toString()

        var resStr = iconPackResMap[componentName]
        if (resStr.isNullOrBlank()) {
            val start = componentName.indexOf("{") + 1
            val end = componentName.indexOf("}", start)
            if (end > start) {
                resStr = componentName.substring(start, end)
                    .lowercase(Locale.getDefault())
                    .replace("/", "_")
                    .replace(".", "_")
            }
        }

        if (resStr.isNullOrBlank()) return null
        val id = iconPackRes.getIdentifier(resStr, "drawable", this.packageName)
        if (id == 0) return null

        return ResourcesCompat.getDrawable(iconPackRes, id, null)
    }

    private fun getDrawableFromName(name: String) : Drawable? {
        if (name.isBlank()) return null
        val id = iconPackRes.getIdentifier(name, "drawable", this.packageName)
        if (id == 0) return null
        return ResourcesCompat.getDrawable(iconPackRes, id, null)
    }

    fun applyIconPackTheme(drawable: Drawable) : Drawable {
        if (commonBackImages.isEmpty()) {
            return drawable
        }

        // Pick one of the pack's back plates at random. This is intentionally non-deterministic:
        // a given app may show a different plate between widget refreshes. Kept as-is to match
        // legacy behavior; switch to a package-name hash if a stable plate per app is ever wanted.
        val backBitmap = commonBackImages.random()
        val w = backBitmap.width
        val h = backBitmap.height
        val outputBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap).apply { drawBitmap(backBitmap, 0f, 0f, null) }

        // app image: a raw app icon (esp. adaptive) only paints its content in the inner safe zone
        // of its own canvas, so drawing it at full plate size left the logo small and top-biased
        // with the iconback showing around it. Trim to the actual content, scale to the pack's
        // scale factor, and center it on the back plate.
        val appBitmap = drawable.toForegroundBitmap()
        val content = appBitmap.contentBounds()
        val srcW = content.width()
        val srcH = content.height()
        if (srcW > 0 && srcH > 0) {
            // contain-fit within (plate * factor), preserving aspect, then center.
            // Guard the factor: a missing/zero `scale` in appfilter.xml would otherwise scale the
            // logo to nothing.
            val factor = if (commonFactor > 0f) commonFactor else 1f
            val targetW = w * factor
            val targetH = h * factor
            val scale = min(targetW / srcW, targetH / srcH)
            val dstW = srcW * scale
            val dstH = srcH * scale
            val dstLeft = (w - dstW) * 0.5f
            val dstTop = (h - dstH) * 0.5f
            val dst = RectF(dstLeft, dstTop, dstLeft + dstW, dstTop + dstH)
            canvas.drawBitmap(appBitmap, content, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        }

        // mask
        commonMask?.let { mask ->
            canvas.drawBitmap(mask, 0f, 0f, maskPaint)
        }

        // front(upon)
        commonFrontImage?.let { front ->
            canvas.drawBitmap(front, 0f, 0f, null)
        }

        return outputBitmap.toDrawable(FrequawApp.appContext.resources)
    }
}

// Minimum alpha (0-255) a pixel must have to count as icon content when measuring bounds. Set
// high enough to exclude anti-aliased edges and adaptive-icon drop shadows (which are typically
// well under ~35% opacity) so the solid logo, not its shadow, drives sizing and centering. Too
// low and the shadow gets included, biasing the content box downward (a circle clip then looks
// cropped at the bottom); too high and faint-but-real logo pixels get trimmed.
private const val CONTENT_ALPHA_THRESHOLD = 96

/**
 * Rasterizes the meaningful part of this drawable. For an adaptive icon only the FOREGROUND layer
 * is drawn (its logo); the flat background is dropped so callers can place the logo on their own
 * surface or scale it to fill. Everything else is rasterized whole.
 */
private fun Drawable.toForegroundBitmap(): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && this is AdaptiveIconDrawable) {
        val fg = foreground
        if (fg != null) {
            val fw = fg.intrinsicWidth.takeIf { it > 0 } ?: intrinsicWidth
            val fh = fg.intrinsicHeight.takeIf { it > 0 } ?: intrinsicHeight
            if (fw > 0 && fh > 0) {
                val bmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
                fg.setBounds(0, 0, fw, fh)
                fg.draw(Canvas(bmp))
                return bmp
            }
        }
    }
    return toBitmap(config = Bitmap.Config.ARGB_8888)
}

/**
 * Returns the bounding [Rect] of this bitmap's non-transparent pixels (the real icon content),
 * ignoring the transparent safe-zone margin a raw/adaptive app icon carries. Falls back to the
 * full bitmap rect when it is fully transparent.
 */
private fun Bitmap.contentBounds(): Rect {
    if (width == 0 || height == 0) return Rect(0, 0, width, height)

    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)

    var left = width; var top = height; var right = -1; var bottom = -1
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            if ((pixels[row + x] ushr 24) > CONTENT_ALPHA_THRESHOLD) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
    }
    if (right < left || bottom < top) return Rect(0, 0, width, height) // fully transparent
    return Rect(left, top, right + 1, bottom + 1)
}

/**
 * Returns a copy of this bitmap with its opaque content scaled to cover the full bounds (centered,
 * overflow cropped), so a following shape clip is fully covered instead of leaving a flat/empty
 * edge where the source had a transparent margin. Returns the original unchanged when the content
 * already fills the bounds (or the bitmap is fully transparent), so full-bleed icons are untouched.
 */
private fun Bitmap.coverFilled(): Bitmap {
    val bounds = contentBounds()
    val cw = bounds.width()
    val ch = bounds.height()
    if (cw <= 0 || ch <= 0) return this
    if (bounds.left == 0 && bounds.top == 0 && cw == width && ch == height) return this // already full

    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { density = this@coverFilled.density }
    val scale = maxOf(width.toFloat() / cw, height.toFloat() / ch)
    val dstW = cw * scale
    val dstH = ch * scale
    val dstLeft = (width - dstW) * 0.5f
    val dstTop = (height - dstH) * 0.5f
    val dst = RectF(dstLeft, dstTop, dstLeft + dstW, dstTop + dstH)
    Canvas(out).drawBitmap(this, bounds, dst, Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}
