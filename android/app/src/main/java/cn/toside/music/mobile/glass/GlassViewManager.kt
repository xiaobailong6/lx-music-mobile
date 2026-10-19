package cn.toside.music.mobile.glass

import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * RN 侧 `LXGlassView` 的 ViewManager。
 * 每个 prop 都直接写进 [GlassView] 的属性，真正的渲染和状态缓存全在 View 里。
 */
class GlassViewManager : SimpleViewManager<GlassView>() {

  override fun getName(): String = "LXGlassView"

  override fun createViewInstance(reactContext: ThemedReactContext): GlassView = GlassView(reactContext)

  @ReactProp(name = "sourceUri")
  fun setSourceUri(view: GlassView, sourceUri: String?) {
    view.sourceUri = sourceUri
  }

  @ReactProp(name = "sourceResource")
  fun setSourceResource(view: GlassView, sourceResource: String?) {
    view.sourceResource = sourceResource
  }

  @ReactProp(name = "sourceHeaders")
  fun setSourceHeaders(view: GlassView, sourceHeaders: String?) {
    view.sourceHeaders = sourceHeaders
  }

  @ReactProp(name = "cropX", defaultFloat = 0f)
  fun setCropX(view: GlassView, cropX: Float) {
    view.cropX = cropX
  }

  @ReactProp(name = "cropY", defaultFloat = 0f)
  fun setCropY(view: GlassView, cropY: Float) {
    view.cropY = cropY
  }

  @ReactProp(name = "boxWidth", defaultFloat = 0f)
  fun setBoxWidth(view: GlassView, boxWidth: Float) {
    view.boxWidth = boxWidth
  }

  @ReactProp(name = "boxHeight", defaultFloat = 0f)
  fun setBoxHeight(view: GlassView, boxHeight: Float) {
    view.boxHeight = boxHeight
  }

  @ReactProp(name = "blurRadius", defaultFloat = 20f)
  fun setBlurRadius(view: GlassView, blurRadius: Float) {
    view.blurRadius = blurRadius
  }

  @ReactProp(name = "tintColor")
  fun setTintColor(view: GlassView, tintColor: String?) {
    view.tintColor = tintColor
  }

  @ReactProp(name = "highlight", defaultFloat = 0f)
  fun setHighlight(view: GlassView, highlight: Float) {
    view.highlight = highlight
  }

  @ReactProp(name = "rimColor")
  fun setRimColor(view: GlassView, rimColor: String?) {
    view.rimColor = rimColor
  }

  @ReactProp(name = "cornerRadius", defaultFloat = 0f)
  fun setCornerRadius(view: GlassView, cornerRadius: Float) {
    view.cornerRadius = cornerRadius
  }

  @ReactProp(name = "refraction", defaultFloat = 0f)
  fun setRefraction(view: GlassView, refraction: Float) {
    view.refraction = refraction
  }
}
