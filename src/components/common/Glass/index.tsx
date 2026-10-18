import {
  createContext,
  forwardRef,
  useCallback,
  useContext,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import {
  Image,
  View,
  type ImageSourcePropType,
  type ImageStyle,
  type StyleProp,
  type ViewProps,
  type ViewStyle,
} from 'react-native'
import { useTheme } from '@/store/theme/hook'

/**
 * 液态玻璃（Liquid Glass）实现
 *
 * 核心思路：不做“半透明面板 + 壁纸透底”，而是真的把面板背后的壁纸模糊一遍。
 * 做法是每个玻璃面板内部再画一份「全屏壁纸」，用负偏移把它对齐到壁纸的原始位置，
 * 加上 blurRadius 就得到“这块面板下方的背景被模糊”的真实效果，最后叠上
 * 底色（tint）、顶部高光带、四边 rim 光来模拟玻璃的折射与高光。
 *
 * 为什么降采样：Android 的 ScriptIntrinsicBlur 半径上限是 25px，
 * 在 1/DOWNSCALE 尺寸的位图上模糊再放大 DOWNSCALE 倍，等效模糊半径 ×DOWNSCALE，
 * 同时模糊位图面积只有 1/DOWNSCALE²，并且所有面板共用同一份模糊缓存。
 */
const DOWNSCALE = 2

/** 顶部高光带的分层数：用多条不同透明度的色带模拟线性渐变（RN 无原生渐变） */
const HIGHLIGHT_BANDS = 6

export interface GlassBackdropConfig {
  /** 是否启用玻璃效果（由主题 token `ui-glass` 驱动） */
  enabled: boolean
  /** 壁纸源，与 PageContent 里画的那张完全一致 */
  source?: ImageSourcePropType
  /** 壁纸渲染尺寸（= 窗口尺寸），用于裁切对齐 */
  width: number
  height: number
  /** 默认模糊半径（针对降采样后的位图） */
  blurRadius: number
}

interface GlassBackdropValue extends GlassBackdropConfig {
  /** 背景根视图在窗口中的位置，玻璃面板用它换算自身相对坐标 */
  origin: { x: number, y: number }
}

const GlassBackdropContext = createContext<GlassBackdropValue | null>(null)

interface ProviderProps extends GlassBackdropConfig {
  children: ReactNode
}

/**
 * 背景提供者：挂在 PageContent 根部，负责测量背景原点并把壁纸信息下发。
 */
export const GlassBackdropProvider = ({ enabled, source, width, height, blurRadius, children }: ProviderProps) => {
  const rootRef = useRef<View | null>(null)
  const [origin, setOrigin] = useState({ x: 0, y: 0 })

  const measureOrigin = useCallback(() => {
    const node = rootRef.current
    if (!node) return
    node.measureInWindow((x, y) => {
      setOrigin(prev => (prev.x === x && prev.y === y ? prev : { x, y }))
    })
  }, [])

  useEffect(() => {
    measureOrigin()
  }, [measureOrigin, width, height])

  const value = useMemo<GlassBackdropValue>(() => ({
    enabled,
    source,
    width,
    height,
    blurRadius,
    origin,
  }), [enabled, source, width, height, blurRadius, origin])

  return (
    <GlassBackdropContext.Provider value={value}>
      {/* collapsable={false}：Android 上必须保留真实原生视图，否则 measureInWindow 拿不到坐标 */}
      <View ref={rootRef} style={{ flex: 1 }} collapsable={false} onLayout={measureOrigin}>
        {children}
      </View>
    </GlassBackdropContext.Provider>
  )
}

/**
 * 作用域：RN 的 Modal 会保留 React context，但原生视图树是独立的，
 * 因此 Modal 内部需要重新挂一个根视图来测量坐标（配置沿用外层的）。
 */
export const GlassBackdropScope = ({ children }: { children: ReactNode }) => {
  const parent = useContext(GlassBackdropContext)
  const rootRef = useRef<View | null>(null)
  const [origin, setOrigin] = useState({ x: 0, y: 0 })

  const measureOrigin = useCallback(() => {
    const node = rootRef.current
    if (!node) return
    node.measureInWindow((x, y) => {
      setOrigin(prev => (prev.x === x && prev.y === y ? prev : { x, y }))
    })
  }, [])

  useEffect(() => {
    measureOrigin()
  }, [measureOrigin])

  const value = useMemo<GlassBackdropValue | null>(() => {
    if (!parent) return null
    return { ...parent, origin }
  }, [parent, origin])

  if (!value) return <>{children}</>

  return (
    <GlassBackdropContext.Provider value={value}>
      <View ref={rootRef} style={{ flex: 1 }} collapsable={false} onLayout={measureOrigin}>
        {children}
      </View>
    </GlassBackdropContext.Provider>
  )
}

export const useGlassBackdrop = () => useContext(GlassBackdropContext)

export interface GlassSurfaceProps extends ViewProps {
  /** 圆角半径 */
  radius?: number
  /** 玻璃底色（叠在模糊背景之上），默认取主题 `glass-tint` */
  tint?: string
  /** 模糊半径（针对降采样后的位图），默认取主题 `glass-blur` */
  blurRadius?: number
  /** 顶部高光强度 0~1，默认取主题 `glass-highlight` */
  highlight?: number
  /** 是否绘制 1px 边缘高光，默认 true */
  rim?: boolean
  /** 未启用玻璃时使用的背景色，默认取主题 `c-content-background` */
  fallbackColor?: string
  style?: StyleProp<ViewStyle>
  children?: ReactNode
}

interface Rect { x: number, y: number }

export const GlassSurface = forwardRef<View, GlassSurfaceProps>(({
  radius = 0,
  tint,
  blurRadius,
  highlight,
  rim = true,
  fallbackColor,
  style,
  children,
  onLayout,
  ...props
}, fwdRef) => {
  const theme = useTheme()
  const backdrop = useGlassBackdrop()
  const innerRef = useRef<View | null>(null)
  const [rect, setRect] = useState<Rect | null>(null)

  useImperativeHandle(fwdRef, () => innerRef.current!, [])
  const enabled = !!backdrop?.enabled && !!backdrop.source
  const originX = backdrop?.origin.x ?? 0
  const originY = backdrop?.origin.y ?? 0

  const measure = useCallback(() => {
    const node = innerRef.current
    if (!node) return
    node.measureInWindow((x, y) => {
      const nextX = x - originX
      const nextY = y - originY
      setRect(prev => (
        prev && Math.abs(prev.x - nextX) < 0.5 && Math.abs(prev.y - nextY) < 0.5
          ? prev
          : { x: nextX, y: nextY }
      ))
    })
  }, [originX, originY])

  useEffect(() => {
    if (!enabled) {
      setRect(null)
      return
    }
    measure()
  }, [enabled, measure])

  const handleLayout = useCallback((e: Parameters<NonNullable<ViewProps['onLayout']>>[0]) => {
    measure()
    onLayout?.(e)
  }, [measure, onLayout])

  const themeTint = theme['glass-tint']
  const themeBlur = Number(theme['glass-blur'])
  const themeHighlight = Number(theme['glass-highlight'])

  const resolvedTint = tint ?? themeTint ?? 'rgba(255, 255, 255, 0.4)'
  const resolvedBlur = blurRadius ?? (Number.isFinite(themeBlur) && themeBlur > 0 ? themeBlur : (backdrop?.blurRadius ?? 20))
  const resolvedHighlight = highlight ?? (Number.isFinite(themeHighlight) ? themeHighlight : 0.45)
  const resolvedFallback = fallbackColor ?? theme['c-content-background']

  const highlightBands = useMemo(() => {
    if (!enabled || resolvedHighlight <= 0) return null
    return Array.from({ length: HIGHLIGHT_BANDS }, (_, i) => (
      <View
        key={i}
        style={{
          flex: 1,
          backgroundColor: '#fff',
          opacity: Math.pow(1 - i / HIGHLIGHT_BANDS, 2) * resolvedHighlight,
        }}
      />
    ))
  }, [enabled, resolvedHighlight])

  const backdropStyle = useMemo<ImageStyle | null>(() => {
    if (!enabled || !rect || !backdrop) return null
    const { width, height } = backdrop
    if (!width || !height) return null
    // 把降采样后的壁纸放大 DOWNSCALE 倍并让「壁纸坐标 (rect.x, rect.y)」落在面板 (0,0)：
    // 缩放以子视图中心为轴，所以中心必须放在 (width/2 - rect.x, height/2 - rect.y)
    return {
      position: 'absolute',
      width: width / DOWNSCALE,
      height: height / DOWNSCALE,
      left: width / 2 - rect.x - width / (2 * DOWNSCALE),
      top: height / 2 - rect.y - height / (2 * DOWNSCALE),
      transform: [{ scale: DOWNSCALE }],
    }
  }, [enabled, rect, backdrop])

  const rimColor = theme['glass-rim'] ?? 'rgba(255, 255, 255, 0.6)'

  return (
    <View
      ref={innerRef}
      onLayout={handleLayout}
      style={[
        // 只在启用玻璃时改变布局行为：未启用时保持原样式，绝不影响其他主题
        enabled ? { overflow: 'hidden' } : null,
        enabled && radius ? { borderRadius: radius } : null,
        // 玻璃就绪前先用底色兜底，避免出现一帧透明闪烁
        enabled && backdropStyle ? null : { backgroundColor: resolvedFallback },
        style,
      ]}
      {...props}
    >
      {
        backdropStyle && backdrop?.source
          ? <View pointerEvents="none" style={{ position: 'absolute', left: 0, top: 0, right: 0, bottom: 0, overflow: 'hidden' }}>
              <Image
                source={backdrop.source}
                resizeMode="cover"
                blurRadius={resolvedBlur}
                fadeDuration={0}
                style={backdropStyle}
              />
              {/* 玻璃底色 */}
              <View style={{ position: 'absolute', left: 0, top: 0, right: 0, bottom: 0, backgroundColor: resolvedTint }} />
              {/* 顶部高光：模拟玻璃受光面 */}
              <View style={{ position: 'absolute', left: 0, top: 0, right: 0, height: '52%', flexDirection: 'column' }}>
                {highlightBands}
              </View>
              {/* 边缘高光（用绝对定位细线，避免 border 影响布局尺寸） */}
              {
                rim
                  ? <>
                      <View style={{ position: 'absolute', left: 0, top: 0, right: 0, height: 1, backgroundColor: rimColor }} />
                      <View style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: 1, backgroundColor: rimColor, opacity: 0.6 }} />
                      <View style={{ position: 'absolute', right: 0, top: 0, bottom: 0, width: 1, backgroundColor: rimColor, opacity: 0.35 }} />
                      <View style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: 1, backgroundColor: '#fff', opacity: 0.18 }} />
                    </>
                  : null
              }
            </View>
          : null
      }
      {children}
    </View>
  )
})

export default GlassSurface
