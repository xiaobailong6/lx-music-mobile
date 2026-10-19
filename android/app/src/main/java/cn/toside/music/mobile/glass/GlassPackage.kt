package cn.toside.music.mobile.glass

import android.os.Build
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.uimanager.ViewManager

/**
 * 极小的常量模块：让 RN 侧在挂载原生玻璃视图之前就知道本机到底支持到哪一档，
 * 避免在低版本设备上白挂一个画不出东西的原生视图。
 */
class GlassModule(ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {

  override fun getName(): String = "LXGlass"

  override fun getConstants(): MutableMap<String, Any> = mutableMapOf<String, Any>(
    // 当前系统 API level，便于日志排查
    "sdkInt" to Build.VERSION.SDK_INT,
    // RenderEffect 可用（API 31+，走 RenderNode 硬件模糊）
    "blur" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S),
    // RuntimeShader 可用（API 33+，走 AGSL 折射）
    "refraction" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU),
  )
}

class GlassPackage : ReactPackage {

  override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> =
    listOf(GlassModule(reactContext))

  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
    listOf(GlassViewManager())
}
