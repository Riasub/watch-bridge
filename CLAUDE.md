# watch-bridge

갤럭시의 카카오톡 알림을 애플워치로 보내는 개인용 도구. 회사 Wi-Fi·핫스팟·셀룰러 요금제 없이
블루투스(BLE)로 갤럭시와 워치가 직접 통신한다.

## 구조
- `android/` 갤럭시 앱(Kotlin, 의존성 없음). 카톡 알림 읽기(`KakaoListener`) + BLE 서버(`BridgeService`)
- `watch/` 워치 앱(SwiftUI, watchOS 10+). XcodeGen(`project.yml`)으로 프로젝트를 만든다
- `docs/PROTOCOL.md` 두 앱이 공유하는 BLE 규약. 바꿀 때는 `Protocol.kt` 와 `BridgeProtocol.swift` 를 함께 고친다
- `.github/workflows/` `android.yml`(APK), `watch.yml`(macOS 컴파일 확인)

## 규칙
- 갤럭시 앱에 `INTERNET` 권한을 넣지 않는다. 카톡 내용이 네트워크로 나갈 수 없어야 한다.
- 로그에 메시지 내용을 남기지 않는다.
- 비밀 값(.p8, 인증서)은 커밋하지 않는다.

## 빌드
- 갤럭시: `cd android && gradle assembleDebug` (JDK 17+, `ANDROID_HOME` 필요), 테스트 `gradle testDebugUnitTest`
- 워치: 리눅스에서는 컴파일할 수 없다. macOS GitHub Actions(`watch.yml`)로 확인한다

## 알려진 위험
워치 앱이 백그라운드에서 BLE 연결을 얼마나 유지하는지는 실기기에서 시험해야 안다.
워치 앱의 "백그라운드 유지 (시험)"에서 모드별로 비교한다.
