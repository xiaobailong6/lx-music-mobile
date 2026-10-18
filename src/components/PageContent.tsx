import { View } from 'react-native'
import { useTheme } from '@/store/theme/hook'
import ImageBackground from '@/components/common/ImageBackground'
import { useWindowSize } from '@/utils/hooks'
import { useMemo } from 'react'
import { scaleSizeAbsHR } from '@/utils/pixelRatio'
import { defaultHeaders } from './common/Image'
import SizeView from './SizeView'
import { useBgPic } from '@/store/common/hook'
import { GlassBackdropProvider } from './common/Glass'

interface Props {
  children: React.ReactNode
}

const BLUR_RADIUS = Math.max(scaleSizeAbsHR(18), 10)

export default ({ children }: Props) => {
  const theme = useTheme()
  const windowSize = useWindowSize()
  const pic = useBgPic()
  // const [wh, setWH] = useState<{ width: number | string, height: number | string }>({ width: '100%', height: Dimensions.get('screen').height })

  // 固定宽高度 防止弹窗键盘时大小改变导致背景被缩放
  // useEffect(() => {
  //   const onChange = () => {
  //     setWH({ width: '100%', height: '100%' })
  //   }

  //   const changeEvent = Dimensions.addEventListener('change', onChange)
  //   return () => {
  //     changeEvent.remove()
  //   }
  // }, [])
  // const handleLayout = (e: LayoutChangeEvent) => {
  //   // console.log('handleLayout', e.nativeEvent)
  //   // console.log(Dimensions.get('screen'))
  //   setWH({ width: e.nativeEvent.layout.width, height: Dimensions.get('screen').height })
  // }
  // console.log('render page content')

  const themeComponent = useMemo(() => (
    <View style={{ flex: 1, overflow: 'hidden' }}>
      <ImageBackground
        style={{ position: 'absolute', left: 0, top: 0, height: windowSize.height, width: windowSize.width, backgroundColor: theme['c-content-background'] }}
        source={theme['bg-image']}
        resizeMode="cover"
      >
      </ImageBackground>
      <View style={{ flex: 1, flexDirection: 'column', backgroundColor: theme['c-main-background'] }}>
        {children}
      </View>
    </View>
  ), [children, theme, windowSize.height, windowSize.width])
  const picComponent = useMemo(() => {
    return (
      <View style={{ flex: 1, overflow: 'hidden' }}>
        <ImageBackground
          style={{ position: 'absolute', left: 0, top: 0, height: windowSize.height, width: windowSize.width, backgroundColor: theme['c-content-background'] }}
          source={{ uri: pic!, headers: defaultHeaders }}
          resizeMode="cover"
          blurRadius={BLUR_RADIUS}
        >
          <View style={{ flex: 1, flexDirection: 'column', backgroundColor: theme['c-content-background'], opacity: 0.76 }}></View>
        </ImageBackground>
        <View style={{ flex: 1, flexDirection: 'column' }}>
          {children}
        </View>
      </View>
    )
  }, [children, pic, theme, windowSize.height, windowSize.width])

  // 液态玻璃：把壁纸信息下发给所有 GlassSurface，面板据此复制背景并做真实模糊
  const glassBackdropSource = pic ? { uri: pic, headers: defaultHeaders } : theme['bg-image']
  const glassEnabled = theme['ui-glass'] === '1'
  const glassBlur = Number(theme['glass-blur'])

  return (
    <>
      <SizeView />
      <GlassBackdropProvider
        enabled={glassEnabled}
        source={glassBackdropSource}
        width={windowSize.width}
        height={windowSize.height}
        blurRadius={Number.isFinite(glassBlur) && glassBlur > 0 ? glassBlur : 18}
      >
        {pic ? picComponent : themeComponent}
      </GlassBackdropProvider>
    </>
  )
}
