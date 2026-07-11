from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "electronic-muyu/app/src/main/java/app/electronicmuyu/android/service/MuyuForegroundService.kt"
MANUAL = ROOT / "electronic-muyu/SECURE_PAIRING_MANUAL_TEST.md"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")


replace_once(
    SERVICE,
    '''                MuyuConnectionRepository.setPartnerOnline(isOnline)
                if (foregroundStarted) {
                    updateForegroundNotification(wsClient.connectionState.value)
                }
''',
    '''                MuyuConnectionRepository.setPartnerOnline(isOnline)
                if (foregroundStarted) {
                    updateForegroundNotification(wsClient.connectionState.value)
                }
                if (isOnline) schedulePendingTapFlush()
'''
)
replace_once(
    SERVICE,
    '''        if (MuyuConnectionRepository.connectionState.value != ConnectionState.CONNECTED) return false
''',
    '''        if (!shouldFlushPendingTaps(
                MuyuConnectionRepository.connectionState.value,
                MuyuConnectionRepository.partnerOnline.value
            )
        ) return false
'''
)
replace_once(
    SERVICE,
    '''        if (MuyuConnectionRepository.connectionState.value == ConnectionState.CONNECTED) {
            schedulePendingTapFlush()
        }
''',
    '''        if (shouldFlushPendingTaps(
                MuyuConnectionRepository.connectionState.value,
                MuyuConnectionRepository.partnerOnline.value
            )
        ) {
            schedulePendingTapFlush()
        }
'''
)
replace_once(
    SERVICE,
    '''                while (MuyuConnectionRepository.connectionState.value == ConnectionState.CONNECTED) {
''',
    '''                while (shouldFlushPendingTaps(
                        MuyuConnectionRepository.connectionState.value,
                        MuyuConnectionRepository.partnerOnline.value
                    )
                ) {
'''
)
replace_once(
    MANUAL,
    '''- [ ] 对方上线、离线和重新连接时，主界面与常驻通知状态及时更新；离线期间的提醒不补发。
''',
    '''- [ ] 对方上线、离线和重新连接时，主界面与常驻通知状态及时更新；短暂离线 10 秒内暂存，持续离线不补发。
'''
)

print("peer-online queue gate applied")
