// Cast-to-TV button rendered on the edge of every video (watch, embed and
// fullscreen; the button lives inside #movie_player so it survives YouTube's
// own fullscreen handling). Android only, and only when the native shell
// reports Cast support (full flavor with Play services); everywhere else this
// is a no-op.
//
// Tap: pause locally and send the current video to the TV at the current
// position. If no TV is connected yet, the native side opens the device
// picker and starts the video once one is picked. Tapping again while
// connected switches the TV to the current video; long-press disconnects.

const BUTTON_ID = 'nou-cast-button'
const STYLE_ID = 'nou-cast-style'
const CONNECTED_POLL_MS = 2500

let styleInstalled = false
let availability: boolean | null = null
let connectedPoll: ReturnType<typeof setInterval> | null = null

function bridgeToken(): string | undefined {
  return (window as any).NouTubeToken
}

function isCastAvailable(): boolean {
  if (availability !== null) {
    return availability
  }
  try {
    availability = window.NouTubeI?.isCastAvailable?.(bridgeToken() as string) === true
  } catch {
    availability = false
  }
  return availability
}

function isCastConnected(): boolean {
  try {
    return window.NouTubeI?.isCastConnected?.(bridgeToken() as string) === true
  } catch {
    return false
  }
}

function installStyle() {
  if (styleInstalled || document.getElementById(STYLE_ID)) {
    styleInstalled = true
    return
  }
  styleInstalled = true
  const style = document.createElement('style')
  style.id = STYLE_ID
  style.textContent = `
    #${BUTTON_ID} {
      position: absolute;
      top: 12px;
      right: 12px;
      width: 44px;
      height: 44px;
      border-radius: 50%;
      border: none;
      padding: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      background: rgba(0, 0, 0, 0.55);
      cursor: pointer;
      z-index: 60;
      -webkit-tap-highlight-color: transparent;
    }
    #${BUTTON_ID}:active {
      background: rgba(0, 0, 0, 0.75);
    }
    #${BUTTON_ID} svg {
      width: 24px;
      height: 24px;
      fill: #fff;
      pointer-events: none;
    }
    #${BUTTON_ID}.nou-cast-connected {
      background: rgba(204, 0, 0, 0.85);
    }
  `
  document.documentElement.appendChild(style)
}

const CAST_SVG =
  '<svg viewBox="0 0 24 24"><path d="M21 3H3c-1.1 0-2 .9-2 2v3h2V5h18v14h-7v2h7c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM11 18H3v-2.36c1.19-.32 2.36-.63 3.62-.94C9.42 13.94 12 12 12 9h2c0 4.42-1.34 6.9-3 8zm5 0H8v-2.36c2.36-.63 4.47-1.26 6-2.05V18h2v-4.34c.74-.58 1.41-1.22 2-1.94V18z"/></svg>'

function currentVideo(): { videoId: string; title: string; positionMs: number } | null {
  const player = document.getElementById('movie_player') as any
  if (!player?.getVideoData) {
    return null
  }
  const data = player.getVideoData() || {}
  const videoId: string = data.video_id || ''
  if (!videoId) {
    return null
  }
  const title: string = data.title || document.title.replace(/ - YouTube$/, '')
  let positionMs = 0
  try {
    positionMs = Math.max(0, Math.floor((player.getCurrentTime?.() || 0) * 1000))
  } catch {
    positionMs = 0
  }
  return { videoId, title, positionMs }
}

function refreshConnectedState(button: HTMLElement) {
  button.classList.toggle('nou-cast-connected', isCastConnected())
}

function onCastTap(event: Event) {
  event.preventDefault()
  event.stopPropagation()
  const token = bridgeToken() as string
  const video = currentVideo()
  if (!video) {
    return
  }
  try {
    if (isCastConnected()) {
      // Already on a TV: switch it to this video at this position.
      window.NouTubeI?.castVideo?.(
        token,
        `https://www.youtube.com/watch?v=${video.videoId}`,
        video.title,
        video.positionMs,
      )
    } else {
      // Pause the phone; the TV takes over from here.
      const player = document.getElementById('movie_player') as any
      try {
        player?.pauseVideo?.()
      } catch {
        // Non-fatal.
      }
      window.NouTubeI?.castVideo?.(
        token,
        `https://www.youtube.com/watch?v=${video.videoId}`,
        video.title,
        video.positionMs,
      )
    }
  } catch {
    // The native side toasts on failure.
  }
}

function onCastLongPress(event: Event) {
  if (!isCastConnected()) {
    return
  }
  event.preventDefault()
  event.stopPropagation()
  try {
    window.NouTubeI?.stopCasting?.(bridgeToken() as string)
  } catch {
    // The native side toasts on failure.
  }
}

function attachButton(player: HTMLElement) {
  if (player.querySelector(`#${BUTTON_ID}`)) {
    return
  }
  installStyle()
  const button = document.createElement('button')
  button.id = BUTTON_ID
  button.type = 'button'
  button.setAttribute('aria-label', 'Cast to TV')
  button.innerHTML = CAST_SVG
  button.addEventListener('click', onCastTap, true)
  button.addEventListener('contextmenu', onCastLongPress, true)
  player.appendChild(button)
  refreshConnectedState(button)
  if (connectedPoll === null) {
    connectedPoll = setInterval(() => {
      const el = document.getElementById(BUTTON_ID)
      if (el) {
        refreshConnectedState(el)
      }
    }, CONNECTED_POLL_MS)
  }
}

function scan(root: ParentNode) {
  if (!isCastAvailable()) {
    return
  }
  const player = root.querySelector?.('#movie_player')
  if (player instanceof HTMLElement) {
    attachButton(player)
  }
}

export function installCastButton() {
  if (!window.isAndroid) {
    return
  }
  // The bridge may not exist yet when the content script runs; availability
  // is resolved lazily on first scan and then cached.
  scan(document)
  const observer = new MutationObserver(() => scan(document))
  observer.observe(document.documentElement, { childList: true, subtree: true })
}
