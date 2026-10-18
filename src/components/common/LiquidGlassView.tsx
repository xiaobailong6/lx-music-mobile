import { memo } from 'react'
import { View, StyleSheet, type StyleProp, type ViewStyle, type ViewProps } from 'react-native'
import { Canvas, Fill, Group, ImageShader, Blur, useImage } from '@shopify/react-native-skia'

import { useTheme } from '@/store/theme/hook'


export interface LiquidGlassViewProps extends ViewProps {
  children?: React.ReactNode
  /**
   * 覆盖默认的玻璃 tint 底色（缺省取主题的 c-main-background 半透明色）
   */
  tint?: string
  /**
   * 模糊强度 sigma，越大越模糊（默认 24）
   */
  blur?: number
  /**
   * 圆角半径，需与外层布局一致（默认 16）
   */
  radius?: number
  /**
   * 是否去掉描边与高光（默认 false）
   */
  borderless?: boolean
}

const styles = StyleSheet.create({
  container: {
    overflow: 'hidden',
    borderWidth: 1,
  },
  tintLayer: {
    ...StyleSheet.absoluteFillObject,
  },
  highlight: {
    position: 'absolute',
    left: 0,
    right: 0,
    top: 0,
    height: 1.5,
  },
  content: {
    flex: 1,
  },
})

/**
 * 液态玻璃容器：
 * 1. 用 Skia 对主题壁纸做实时高斯模糊（真模糊，非半透明模拟）
 * 2. 叠加主题半透明 tint（c-main-background）
 * 3. 顶部高光 + 白色描边模拟玻璃边缘反光
 *
 * 若当前主题无壁纸（bg-image 为空），自动退化为纯 tint 玻璃，不渲染 Canvas
 */
export default memo(({
  children,
  style,
  tint,
  blur = 24,
  radius = 16,
  borderless = false,
  ...viewProps
}: LiquidGlassViewProps) => {
  const theme = useTheme()
  const isDark = theme.isDark
  // 当前主题壁纸（liquid_glass / naruto / black 等带壁纸主题会有值）
  const bgImage = theme['bg-image']
  // useImage 只接受 number（require 资源）、uri 字符串或 null
  const imageSource = bgImage == null
    ? null
    : typeof bgImage === 'number'
      ? bgImage
      : ((bgImage as { uri?: string }).uri ?? null)
  const image = useImage(imageSource)

  const glassTint = tint ?? theme['c-main-background']
  const borderColor = isDark ? 'rgba(255, 255, 255, 0.12)' : 'rgba(255, 255, 255, 0.42)'
  const highlightColor = isDark ? 'rgba(255, 255, 255, 0.06)' : 'rgba(255, 255, 255, 0.45)'

  return (
    <View
      style={[
        styles.container,
        {
          borderRadius: radius,
          borderColor: borderless ? 'transparent' : borderColor,
        },
        style,
      ]}
      {...viewProps}
    >
      {
        image
          ? (
            <Canvas style={StyleSheet.absoluteFill}>
              <Group>
                {/* 滤镜作为 Group 子节点，作用于其后的绘制操作 */}
                <Blur blur={blur} />
                <Fill>
                  <ImageShader image={image} fit="cover" />
                </Fill>
              </Group>
            </Canvas>
          )
          : null
      }
      <View style={[styles.tintLayer, { backgroundColor: glassTint, borderRadius: radius }]} />
      {
        borderless
          ? null
          : <View style={[styles.highlight, { backgroundColor: highlightColor, borderTopLeftRadius: radius, borderTopRightRadius: radius }]} />
      }
      <View style={styles.content}>{children}</View>
    </View>
  )
})
