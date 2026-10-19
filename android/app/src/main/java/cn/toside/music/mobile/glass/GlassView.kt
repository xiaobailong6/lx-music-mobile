package cn.toside.music.mobile.glass

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.view.View
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 原生「液态玻璃」视图。
 *
 * RN 侧只负责把「这块面板在壁纸坐标系里的位置 + 面板尺寸 + 各种视觉参数」传进来，
 * 真正的「取壁纸 → 裁切 → 模糊 → 折射 → 叠色/高光/rim」全部在这里用原生 API 完成：
 *
 *  - API 31+：RenderNode + RenderEffect（硬件渲染，模糊在 GPU 上做，代价与半径无关）
 *  - API 33+：RenderEffect 换成 AGSL RuntimeShader，额外做边缘折射（lens）效果
 *  - API 21~30：没有 RenderEffect，改用「先降采样到 1/8 → 再线性放大」近似高斯模糊
 *
 * 所有 dp 入参都会乘 [den] 变成 px，与 [getWidth] 处于同一坐标系。
 */
class GlassView(context: Context) : View(context) {

  companion object {
    /** 顶部高光带的分层数（与 RN 侧保持一致，用多条色带模拟线性渐变） */
    private const val HIGHLIGHT_BANDS = 6
    /** 顶部高光带占整体高度的比例 */
    private const val HIGHLIGHT_HEIGHT_RATIO = 0.52f
    /** 低版本近似模糊的降采样倍数 */
    private const val LOW_RES_DIVISOR = 8

    /**
     * AGSL 着色器源码：content 是下层内容（已裁切好的壁纸），
     * 先按 9 点采样做一次近似高斯模糊，再按到中心的距离做边缘透镜折射。
     * 注意：#version 必须是最前面一行，前面不能有空行或空格。
     */
    const val SHADER_SRC: String = """#version 320 es
precision mediump float;
uniform shader content;
uniform float2 uSize;
uniform float uBlur;
uniform float uRefract;

half4 tap(float2 p) { return content.eval(clamp(p, float2(0.5), uSize - 0.5)); }

half4 main(float2 coord) {
  float2 halfSize = uSize * 0.5;
  float2 p = coord - halfSize;
  float len = length(p);
  float2 dir = len > 0.001 ? p / len : float2(0.0);
  float lens = max(8.0, min(uSize.x, uSize.y) * 0.16);
  float dist = min(1.0, min(abs(p.x) / halfSize.x, abs(p.y) / halfSize.y));
  float edge = pow(dist, 3.0);
  float2 c = coord + dir * edge * lens * uRefract;
  float r = max(0.5, uBlur);
  float4 col = float4(tap(c)) * 0.2042;
  col += (float4(tap(c + float2(r,0.0))) + float4(tap(c - float2(r,0.0))) + float4(tap(c + float2(0.0,r))) + float4(tap(c - float2(0.0,r)))) * 0.1238;
  col += (float4(tap(c + float2(r,r))) + float4(tap(c - float2(r,r))) + float4(tap(c + float2(r,-r))) + float4(tap(c - float2(r,-r)))) * 0.0751;
  return half4(col);
}
"""

    /**
     * 解析 CSS 颜色字符串（RN 传过来的 token 是 'rgba(255,255,255,0.34)' 这类格式，
     * Android 的 Color.parseColor 不支持 rgba()，所以自己解析）。
     * 支持 #rgb / #argb / #rrggbb / #aarrggbb / rgb() / rgba() / transparent。
     */
    fun parseCssColor(s: String?): Int {
      if (s == null) return Color.TRANSPARENT
      val t = s.trim()
      if (t.isEmpty()) return Color.TRANSPARENT
      if (t.equals("transparent", ignoreCase = true)) return Color.TRANSPARENT
      if (t.startsWith("#")) {
        val hex = t.substring(1)
        return when (hex.length) {
          3 -> Color.rgb(hexDigit(hex[0]) * 17, hexDigit(hex[1]) * 17, hexDigit(hex[2]) * 17)
          4 -> Color.argb(
            hexDigit(hex[0]) * 17, hexDigit(hex[1]) * 17,
            hexDigit(hex[2]) * 17, hexDigit(hex[3]) * 17,
          )
          6 -> {
            val v = hex.toLongOrNull(16) ?: return Color.TRANSPARENT
            (0xFF000000L or v).toInt()
          }
          8 -> {
            val v = hex.toLongOrNull(16) ?: return Color.TRANSPARENT
            v.toInt()
          }
          else -> Color.TRANSPARENT
        }
      }
      if (!t.startsWith("rgb", ignoreCase = true)) return Color.TRANSPARENT
      val open = t.indexOf('(')
      val close = t.lastIndexOf(')')
      if (open < 0 || close <= open) return Color.TRANSPARENT
      val parts = t.substring(open + 1, close).split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }
      if (parts.size < 3) return Color.TRANSPARENT
      var alpha = 255
      if (parts.size >= 4) {
        val a = parts[3]
        val raw = if (a.endsWith("%")) {
          (a.dropLast(1).toFloatOrNull() ?: 100f) * 255f / 100f
        } else {
          val f = a.toFloatOrNull() ?: 1f
          // 规范里 rgba() 的 alpha 是 0~1 的浮点；同时兼容 0~255 的整数写法
          if (f <= 1f) f * 255f else f
        }
        alpha = raw.toInt().coerceIn(0, 255)
      }
      return Color.argb(alpha, cssChannel(parts[0]), cssChannel(parts[1]), cssChannel(parts[2]))
    }

    private fun hexDigit(c: Char): Int = when (c) {
      in '0'..'9' -> c - '0'
      in 'a'..'f' -> c - 'a' + 10
      in 'A'..'F' -> c - 'A' + 10
      else -> 0
    }

    private fun cssChannel(token: String): Int {
      val percent = token.endsWith("%")
      val f = (if (percent) token.dropLast(1) else token).toFloatOrNull() ?: 0f
      val v = if (percent) f * 255f / 100f else f
      return v.toInt().coerceIn(0, 255)
    }

    /** 按比例缩放一个 int 颜色的 alpha 通道（用于 rim 各边不同的透明度） */
    private fun withAlpha(color: Int, factor: Float): Int {
      val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
      return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
  }

  /** dp → px 的换算系数；所有 dp 入参统一乘它 */
  private val den: Float = context.resources.displayMetrics.density

  /** 壁纸 URI（http/file/content 等），与 sourceResource 二选一 */
  var sourceUri: String? = null
    set(value) {
      if (field == value) return
      field = value
      scheduleSourceLoad()
    }

  /** 打平后的 drawable 资源名（release 包里的 require() 图片走这条） */
  var sourceResource: String? = null
    set(value) {
      if (field == value) return
      field = value
      scheduleSourceLoad()
    }

  /** 壁纸下载所需的 HTTP 头，JSON 字符串 */
  var sourceHeaders: String? = null
    set(value) {
      if (field == value) return
      field = value
      if (!sourceUri.isNullOrEmpty()) scheduleSourceLoad()
    }

  /** 本视图左上角在壁纸坐标系里的位置（dp） */
  var cropX: Float = 0f
    set(value) {
      if (field == value) return
      field = value
      updatePipeline()
      invalidate()
    }

  var cropY: Float = 0f
    set(value) {
      if (field == value) return
      field = value
      updatePipeline()
      invalidate()
    }

  /** 壁纸被 RN 铺满时的尺寸（dp），用于计算 cover 缩放 */
  var boxWidth: Float = 0f
    set(value) {
      if (field == value) return
      field = value
      requestLayout()
      updatePipeline()
      invalidate()
    }

  var boxHeight: Float = 0f
    set(value) {
      if (field == value) return
      field = value
      requestLayout()
      updatePipeline()
      invalidate()
    }

  /** 模糊半径（dp） */
  var blurRadius: Float = 20f
    set(value) {
      if (field == value) return
      field = value
      updatePipeline()
      invalidate()
    }

  /** 玻璃底色，CSS 颜色字符串 */
  var tintColor: String? = null
    set(value) {
      if (field == value) return
      field = value
      invalidate()
    }

  /** 顶部高光强度 0~1 */
  var highlight: Float = 0f
    set(value) {
      if (field == value) return
      field = value
      invalidate()
    }

  /** 边缘 rim 颜色，CSS 颜色字符串；为空则不画 rim */
  var rimColor: String? = null
    set(value) {
      if (field == value) return
      field = value
      invalidate()
    }

  /** 圆角半径（dp），内部再裁一次，避免 RN 侧裁剪失效时露直角 */
  var cornerRadius: Float = 0f
    set(value) {
      if (field == value) return
      field = value
      requestLayout()
      invalidate()
    }

  /** 边缘折射强度 0~1（仅 API 33+ 的 AGSL 路径有效） */
  var refraction: Float = 0f
    set(value) {
      val v = value.coerceIn(0f, 1f)
      if (field == v) return
      field = v
      updatePipeline()
      invalidate()
    }

  /** 已解码的壁纸原图（全屏尺寸；不归本视图所有，由缓存管理生命周期） */
  private var bitmap: Bitmap? = null
  /** 自增 token：丢弃过期的后台解码结果，避免快速切换壁纸时错乱 */
  private var loadToken = 0

  /** 「壁纸坐标系 → 位图像素」的源裁切矩形 */
  private val srcRect = Rect()
  private val dstRect = Rect()

  private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
  private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

  /** API 31+ 的 RenderNode 载体（类型擦成 Any? 以免低版本设备加载类时解析不到新 API） */
  private var nodeApi: Any? = null
  /** API <31 时缓存的降采样小位图（近似高斯模糊用），由本视图负责 recycle */
  private var lowSmall: Bitmap? = null
  private val clipPath = Path()

  private val sourceLoadTask = Runnable { performSourceLoad() }

  init {
    setWillNotDraw(false)
  }

  // ---------------------------------------------------------------- 壁纸加载

  private fun scheduleSourceLoad() {
    removeCallbacks(sourceLoadTask)
    post(sourceLoadTask)
  }

  private fun performSourceLoad() {
    val resource = sourceResource
    val uri = sourceUri
    val key = if (!resource.isNullOrEmpty()) resource else (uri ?: "")
    val token = ++loadToken
    if (key.isEmpty()) {
      bitmap = null
      updatePipeline()
      invalidate()
      return
    }
    val appContext = context.applicationContext
    GlassWallpaperCache.load(appContext, key, resource, uri, sourceHeaders) { bmp ->
      // 回调发生在后台线程，切回主线程再改状态
      post {
        if (token != loadToken) return@post
        bitmap = bmp
        updatePipeline()
        invalidate()
      }
    }
  }

  // ---------------------------------------------------------------- 几何计算

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    updatePipeline()
  }

  override fun onDetachedFromWindow() {
    removeCallbacks(sourceLoadTask)
    loadToken++
    releaseLowSmall()
    super.onDetachedFromWindow()
  }

  /**
   * 按 cover 规则，算出「本视图在壁纸上对应的那一块」在位图像素里的范围。
   * boxWidth/boxHeight 是 RN 铺壁纸的尺寸（dp），cropX/cropY 是本视图在其坐标系里的偏移（dp）。
   */
  private fun srcRectFor(bmp: Bitmap, w: Int, h: Int, den: Float): Rect {
    val iw = bmp.width.toFloat()
    val ih = bmp.height.toFloat()
    val bw = boxWidth * den
    val bh = boxHeight * den
    // 壁纸以 cover 方式铺满，缩放系数取两个方向的较大值；box 尺寸未知时退化为按本视图尺寸
    val scale = if (bw > 0f && bh > 0f) max(bw / iw, bh / ih) else max(w / iw, h / ih)
    val safeScale = if (scale <= 0f || scale.isNaN() || scale.isInfinite()) 1f else scale
    val dw = iw * safeScale
    val dh = ih * safeScale
    // cover + 居中：被裁掉的部分对称分布在两侧
    val ox = (bw - dw) / 2f
    val oy = (bh - dh) / 2f
    val sx = (cropX * den - ox) / safeScale
    val sy = (cropY * den - oy) / safeScale
    val sw = w / safeScale
    val sh = h / safeScale

    val maxX = max(0, bmp.width - 1)
    val maxY = max(0, bmp.height - 1)
    val left = floor(sx).toInt().coerceIn(0, maxX)
    val top = floor(sy).toInt().coerceIn(0, maxY)
    val right = ceil(sx + sw).toInt().coerceIn(min(left + 1, bmp.width), bmp.width)
    val bottom = ceil(sy + sh).toInt().coerceIn(min(top + 1, bmp.height), bmp.height)
    return Rect(left, top, right, bottom)
  }

  /** 尺寸/壁纸/裁切/模糊参数变化后，重建渲染管线（RenderNode 或降采样小图） */
  private fun updatePipeline() {
    val bmp = bitmap
    val w = width
    val h = height
    if (bmp == null || w <= 0 || h <= 0) {
      releaseLowSmall()
      return
    }
    srcRect.set(srcRectFor(bmp, w, h, den))
    dstRect.set(0, 0, w, h)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      releaseLowSmall()
      rebuildNode()
    } else {
      nodeApi = null
      rebuildLowSmall(bmp)
    }
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private fun obtainNodeApi(): NodeApi31 {
    val existing = nodeApi as? NodeApi31
    if (existing != null) return existing
    val created = NodeApi31()
    nodeApi = created
    return created
  }

  /** API 31+：把裁切后的壁纸录进 RenderNode，并挂上模糊/折射 effect */
  @RequiresApi(Build.VERSION_CODES.S)
  private fun rebuildNode() {
    val bmp = bitmap ?: return
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return
    obtainNodeApi().rebuild(w, h, bmp, srcRect, blurRadius * den, refraction)
  }

  /** API <31：把裁切结果画进 1/8 大小的小图，线性放大后近似高斯模糊 */
  private fun rebuildLowSmall(bmp: Bitmap) {
    val w = width
    val h = height
    releaseLowSmall()
    if (w <= 0 || h <= 0) return
    val tw = max(1, w / LOW_RES_DIVISOR)
    val th = max(1, h / LOW_RES_DIVISOR)
    try {
      val small = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
      val c = Canvas(small)
      c.drawBitmap(bmp, srcRect, Rect(0, 0, tw, th), imagePaint)
      lowSmall = small
    } catch (t: Throwable) {
      // OOM 等情况下放弃近似模糊，画透明让 RN 的兜底背景生效
      lowSmall = null
    }
  }

  private fun releaseLowSmall() {
    val small = lowSmall
    lowSmall = null
    if (small != null && !small.isRecycled) {
      try {
        small.recycle()
      } catch (t: Throwable) {
        // ignore
      }
    }
  }

  // ---------------------------------------------------------------- 绘制

  override fun onDraw(canvas: Canvas) {
    // 壁纸还没解码好（或解码失败）时什么都不画，交给 RN 侧的兜底底色
    if (bitmap == null) return
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      drawWithNode(canvas)
    } else {
      val small = lowSmall ?: return
      canvas.drawBitmap(small, null, dstRect, imagePaint)
    }
    drawOverlays(canvas, w, h)
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private fun drawWithNode(canvas: Canvas) {
    if (cornerRadius > 0f) {
      canvas.save()
      clipPath.reset()
      clipPath.addRoundRect(
        0f, 0f, width.toFloat(), height.toFloat(),
        cornerRadius * den, cornerRadius * den, Path.Direction.CW,
      )
      canvas.clipPath(clipPath)
      try {
        obtainNodeApi().draw(canvas)
      } finally {
        canvas.restore()
      }
      return
    }
    obtainNodeApi().draw(canvas)
  }

  /** 叠层：底色 tint → 顶部高光带 → 四边 rim 光 */
  private fun drawOverlays(canvas: Canvas, w: Int, h: Int) {
    val fw = w.toFloat()
    val fh = h.toFloat()

    val tint = parseCssColor(tintColor)
    if (Color.alpha(tint) != 0) {
      fillPaint.color = tint
      fillPaint.alpha = Color.alpha(tint)
      canvas.drawRect(0f, 0f, fw, fh, fillPaint)
    }

    if (highlight > 0f) {
      val bandHeight = fh * HIGHLIGHT_HEIGHT_RATIO / HIGHLIGHT_BANDS
      fillPaint.color = Color.WHITE
      for (i in 0 until HIGHLIGHT_BANDS) {
        // 逐段二次方衰减：越靠顶部越亮
        val alpha = ((1f - i / HIGHLIGHT_BANDS.toFloat()).pow(2) * highlight * 255f).toInt()
        if (alpha <= 0) continue
        fillPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawRect(0f, i * bandHeight, fw, (i + 1) * bandHeight, fillPaint)
      }
      fillPaint.alpha = 255
    }

    val rim = parseCssColor(rimColor)
    if (Color.alpha(rim) != 0 && w > 1 && h > 1) {
      // 上边：满透明度
      fillPaint.color = rim
      canvas.drawRect(0f, 0f, fw, 1f, fillPaint)
      // 左右：0.6 透明度
      fillPaint.color = withAlpha(rim, 0.6f)
      canvas.drawRect(0f, 0f, 1f, fh, fillPaint)
      canvas.drawRect(fw - 1f, 0f, fw, fh, fillPaint)
      // 下边：固定的白色微亮线，模拟玻璃底部反射
      fillPaint.color = withAlpha(Color.WHITE, 0.18f)
      canvas.drawRect(0f, fh - 1f, fw, fh, fillPaint)
    }
  }

  /**
   * API 31+ 的渲染实现单独放一个类里：
   * 低版本设备虽然加载不到 RenderNode/RenderEffect，但只要不执行到这里就不会触发解析失败。
   */
  @RequiresApi(Build.VERSION_CODES.S)
  private class NodeApi31 {
    private val node: RenderNode = RenderNode("LXGlassView")
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dst = Rect()

    fun rebuild(w: Int, h: Int, bmp: Bitmap, src: Rect, blurPx: Float, refract: Float) {
      node.setPosition(0, 0, w, h)
      dst.set(0, 0, w, h)
      val c = node.beginRecording(w, h)
      paint.isFilterBitmap = true
      c.drawBitmap(bmp, src, dst, paint)
      node.endRecording()
      node.setRenderEffect(buildEffect(w, h, blurPx, refract))
    }

    fun draw(canvas: Canvas) {
      canvas.drawRenderNode(node)
    }

    /** API 33+ 用 AGSL 着色器（模糊 + 边缘折射）；更低或着色器失败时退回纯模糊 */
    private fun buildEffect(w: Int, h: Int, blurPx: Float, refract: Float): RenderEffect {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        try {
          val shader = RuntimeShader(GlassView.SHADER_SRC)
          shader.setFloatUniform("uSize", w.toFloat(), h.toFloat())
          shader.setFloatUniform("uBlur", blurPx.coerceIn(0.5f, 200f))
          shader.setFloatUniform("uRefract", refract.coerceIn(0f, 1f))
          return RenderEffect.createRuntimeShaderEffect(shader, "content")
        } catch (t: Throwable) {
          // 个别 ROM 上 AGSL 编译失败时不能崩，静默回退到普通模糊
        }
      }
      val r = blurPx.coerceIn(0.1f, 300f)
      return RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
    }
  }
}

/**
 * 壁纸位图缓存 + 后台解码。
 *
 * 所有玻璃面板共用同一份「全屏壁纸」，因此共享同一个 LruCache；
 * 解码固定走单线程，避免多面板同时挂载时并发解码把内存打爆。
 */
object GlassWallpaperCache {
  /** 缓存上限 40MB */
  private const val MAX_BYTES = 40 * 1024 * 1024
  /** 解码后长边上限：超大壁纸先降采样，省内存，玻璃也够用 */
  private const val MAX_EDGE = 2048

  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "LXGlassWallpaper").apply {
      isDaemon = true
      priority = Thread.NORM_PRIORITY - 1
    }
  }

  private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
  }

  /**
   * 取壁纸。
   *
   * @param key 缓存键：有资源名优先用资源名，否则用 uri
   * @param onResult 命中缓存时在调用线程同步回调，未命中时在后台线程回调；调用方负责切主线程
   */
  fun load(
    ctx: Context,
    key: String,
    resource: String?,
    uri: String?,
    headers: String?,
    onResult: (Bitmap?) -> Unit,
  ) {
    val cached = try {
      cache.get(key)
    } catch (t: Throwable) {
      null
    }
    if (cached != null) {
      onResult(cached)
      return
    }
    executor.execute {
      // 任何异常（网络失败、文件不存在、OOM）都只当作「没有壁纸」，绝不向上抛
      val decoded = try {
        decode(ctx, resource, uri, headers)
      } catch (t: Throwable) {
        null
      }
      if (decoded != null) {
        try {
          cache.put(key, decoded)
        } catch (t: Throwable) {
          // 缓存失败不影响本次显示
        }
      }
      onResult(decoded)
    }
  }

  private fun decode(ctx: Context, resource: String?, uri: String?, headers: String?): Bitmap? {
    if (!resource.isNullOrEmpty()) {
      // release 包里 require() 的图片会被 RN 解析成打平后的 drawable 资源名
      val id = ctx.resources.getIdentifier(resource, "drawable", ctx.packageName)
      if (id != 0) return decodeResource(ctx.resources, id)
    }
    if (uri.isNullOrEmpty()) return null
    val lower = uri.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
      return decodeRemote(ctx, uri, headers)
    }
    if (lower.startsWith("content://")) {
      return decodeContent(ctx, uri)
    }
    // file:// 或绝对路径
    return decodeFile(if (lower.startsWith("file://")) uri.substring(7) else uri)
  }

  private fun decodeRemote(ctx: Context, uri: String, headers: String?): Bitmap? {
    var httpConn: HttpURLConnection? = null
    var tmpFile: File? = null
    try {
      val connection = (URL(uri).openConnection() as? HttpURLConnection) ?: return null
      httpConn = connection
      connection.connectTimeout = 10000
      connection.readTimeout = 20000
      if (!headers.isNullOrEmpty()) {
        // RN 侧把 headers 以 JSON 字符串传下来（壁纸防盗链常用 Referer / User-Agent）
        val obj = JSONObject(headers)
        val keys = obj.keys()
        while (keys.hasNext()) {
          val name = keys.next()
          val value = obj.optString(name, "")
          if (value.isNotEmpty()) connection.setRequestProperty(name, value)
        }
      }
      connection.connect()
      val file = File.createTempFile("lx-glass-", ".img", ctx.cacheDir)
      tmpFile = file
      connection.inputStream.use { input ->
        file.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
      }
      // 先落盘再解码，才能用 inJustDecodeBounds 做两段式降采样
      return decodeFile(file.absolutePath)
    } finally {
      try {
        httpConn?.disconnect()
      } catch (t: Throwable) {
        // ignore
      }
      try {
        tmpFile?.delete()
      } catch (t: Throwable) {
        // ignore
      }
    }
  }

  private fun decodeContent(ctx: Context, uri: String): Bitmap? {
    var tmpFile: File? = null
    try {
      val input = ctx.contentResolver.openInputStream(Uri.parse(uri)) ?: return null
      val file = File.createTempFile("lx-glass-", ".img", ctx.cacheDir)
      tmpFile = file
      input.use { stream ->
        file.outputStream().use { out -> stream.copyTo(out, 64 * 1024) }
      }
      return decodeFile(file.absolutePath)
    } finally {
      try {
        tmpFile?.delete()
      } catch (t: Throwable) {
        // ignore
      }
    }
  }

  private fun decodeFile(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply {
      inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(path, opts)
  }

  private fun decodeResource(res: Resources, id: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeResource(res, id, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply {
      inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeResource(res, id, opts)
  }

  /** 让长边不超过 [MAX_EDGE] 的最小 2 的幂采样率 */
  private fun sampleSizeFor(width: Int, height: Int): Int {
    var sample = 1
    val longest = max(width, height)
    while (longest / sample > MAX_EDGE) sample *= 2
    return sample
  }
}