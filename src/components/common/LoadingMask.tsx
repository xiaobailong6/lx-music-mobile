import { useState, useCallback, useMemo, useRef, forwardRef, useImperativeHandle } from 'react'
import { Animated } from 'react-native'
import { useTheme } from '@/store/theme/hook'
import { useI18n } from '@/lang'
import { createStyle } from '@/utils/tools'
import Loading from './Loading'
import { GlassSurface } from '@/components/common/Glass'

// interface LoadingMaskProps {

// }

export interface LoadingMaskType {
  setVisible: (visible: boolean) => void
}

export default forwardRef<LoadingMaskType, {}>((props, ref) => {
  const theme = useTheme()
  const t = useI18n()
  const animFade = useRef(new Animated.Value(0)).current
  const [maskVisible, setMaskVisible] = useState(false)

  useImperativeHandle(ref, () => ({
    setVisible(visible: boolean) {
      if (maskVisible == visible) return
      visible ? handleShow() : handleHide()
    },
  }))

  const handleShow = useCallback(() => {
    // console.log('handleShow')
    setMaskVisible(true)

    Animated.parallel([
      Animated.timing(animFade, {
        toValue: 1,
        duration: 200,
        useNativeDriver: true,
      }),
    ]).start()
  }, [animFade])

  const handleHide = useCallback(() => {
    // Will change fadeAnim value to 0 in 5 seconds
    // console.log('handleHide')
    Animated.parallel([
      Animated.timing(animFade, {
        toValue: 0,
        duration: 300,
        useNativeDriver: true,
      }),
    ]).start((finished) => {
      // console.log(finished)
      if (!finished) return
      setMaskVisible(false)
    })
  }, [animFade])


  const maskComponent = useMemo(() => (
    <Animated.View style={{ ...styles.container, opacity: animFade }}>
      <GlassSurface radius={0} tint={theme['glass-content-tint']} fallbackColor={theme['c-main-background']} style={styles.glass}>
        <Loading size={25} label={t('list_loading')} />
      </GlassSurface>
    </Animated.View>
  ), [animFade, t, theme])

  return maskVisible ? maskComponent : null
})

const styles = createStyle({
  container: {
    position: 'absolute',
    left: 0,
    top: 0,
    width: '100%',
    height: '100%',
    zIndex: 10,
    justifyContent: 'center',
    alignItems: 'center',
    gap: 10,
  },
  glass: {
    flex: 1,
    width: '100%',
    justifyContent: 'center',
    alignItems: 'center',
    gap: 10,
  },
})
