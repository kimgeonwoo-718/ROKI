# ROKI

인터넷 없이 폰 안에서 Gemma 모델로 대화하는 안드로이드 앱입니다.
추론은 Google AI Edge의 [LiteRT-LM](https://developers.google.com/edge/litert-lm/android)으로 기기에서만 실행되고, 대화 내용은 어디로도 전송되지 않습니다.

- 기본 모델: **Gemma 4 E2B** (`litert-community/gemma-4-E2B-it-litert-lm`, 2.59GB, Apache 2.0)
- 첫 실행 때 앱 안에서 모델을 한 번만 내려받고, 그 뒤로는 완전히 오프라인으로 동작합니다.
- 테스트 기기: Galaxy S26 Ultra (Snapdragon 8 Elite Gen 5, RAM 12GB, Android 16)

## 진행 상황

| 단계 | 내용 | 상태 |
|---|---|---|
| 1 | 프로젝트, 앱 내 모델 다운로드, 모델 로딩(GPU→CPU), 한 번 주고받기 | ✅ |
| 2 | 채팅 화면, 스트리밍, 중지, 마크다운, 추론 수준, 길게 눌러 복사/다시 생성 | ✅ |
| 3 | Room 저장, 사이드바 채팅 보관소, 새 채팅, 이름 변경/삭제 | ✅ |
| 4 | 사용자 맞춤 설정(성격·성별·말투·정치성향·답변길이), 설정(화면 모드·온도·시스템 프롬프트), CPU/GPU/RAM 표시, 성능 표시 | ✅ |
| 5 | 다운로드 화면 다듬기 | ✅ (1단계에서 함께 구현) |

## 빌드 환경

| 항목 | 버전 |
|---|---|
| Android Studio | Quail 2 (2026.1.2) 이상 |
| SDK | Android SDK Platform **37.0** (compileSdk 37), targetSdk 36, minSdk 31 |
| JDK | Android Studio 내장 JBR 21 |
| Gradle / AGP / Kotlin | 9.5.0 / 9.3.3 / 2.4.21 |
| LiteRT-LM | `com.google.ai.edge.litertlm:litertlm-android:0.18.0` |

### Android Studio에서
프로젝트 폴더를 열고 ▶ Run을 누르면 됩니다.

### 명령줄에서 (Windows PowerShell)
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

> **빌드 결과물 위치:** 프로젝트가 OneDrive 폴더 안에 있으면 OneDrive 동기화가 `build/` 파일을 잠가서 빌드가 실패합니다.
> 그래서 이 경우 빌드 결과물을 `%LOCALAPPDATA%\roki-build\` 아래에 만들도록 설정돼 있습니다 (`build.gradle.kts`).
> APK: `%LOCALAPPDATA%\roki-build\app\outputs\apk\debug\app-debug.apk`

## 폰에 설치

1. 폰에서 **설정 → 휴대전화 정보 → 소프트웨어 정보 → 빌드번호**를 7번 눌러 개발자 모드를 켭니다.
2. **설정 → 개발자 옵션 → USB 디버깅**을 켜고 USB로 PC에 연결합니다. 폰에 뜨는 허용 창에서 "허용"을 누릅니다.
3. 설치합니다. adb가 PATH에 없으면 전체 경로를 씁니다.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
& $adb install -r "$env:LOCALAPPDATA\roki-build\app\outputs\apk\debug\app-debug.apk"
```

## 모델 파일

### 기본: 앱 안에서 내려받기
앱을 처음 열면 모델 다운로드 화면이 나옵니다.

- 받는 주소: Hugging Face의 고정 커밋 URL (`ModelCatalog.kt`). 로그인이나 토큰이 필요 없습니다.
- 진행률·속도·남은 시간 표시, 다른 앱을 써도 알림창에서 계속 받기 (WorkManager 포그라운드 작업)
- 중간에 끊기면 받은 부분부터 **이어받기** (HTTP Range)
- 시작 전 **저장공간 확인**, 끝난 뒤 **크기 + SHA-256 무결성 검사**
- 모바일 데이터일 때는 받기 전에 확인을 묻습니다.

다운로드 주소를 바꾸려면 `gradle.properties`에 추가합니다 (미러 서버 등):
```properties
roki.modelDownloadUrl=https://example.com/gemma-4-E2B-it.litertlm
```

### 개발용: adb로 직접 넣기
다운로드 없이 PC에 있는 모델 파일을 넣을 수도 있습니다. 앱은 아래 경로를 순서대로 찾습니다.

1. `/sdcard/Android/data/io.github.kimgeonwoo718.roki/files/models/<파일명>` (권장)
2. `/data/local/tmp/llm/<파일명>` (디버그 빌드만)

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
# 앱을 한 번 실행해서 폴더가 만들어진 뒤에:
& $adb shell mkdir -p /sdcard/Android/data/io.github.kimgeonwoo718.roki/files/models
& $adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/io.github.kimgeonwoo718.roki/files/models/
```

파일 크기가 `ModelCatalog.kt`의 값과 정확히 같아야 인식합니다. 앱을 다시 열면 자동으로 찾습니다.

### 다른 모델로 바꾸기
`gradle.properties`의 `roki.modelId` 또는 빌드 옵션으로 고릅니다.

| `roki.modelId` | 모델 | 크기 | 받는 방법 |
|---|---|---|---|
| `gemma-4-e2b` (기본) | Gemma 4 E2B | 2.59GB | 앱 안에서 다운로드 |
| `gemma-3n-e2b` | Gemma 3n E2B int4 | 3.66GB | adb로만 (Hugging Face 로그인 + 라이선스 동의 필요) |
| `gemma-3n-e4b` | Gemma 3n E4B int4 | 4.92GB | adb로만 (같음) |

```powershell
.\gradlew.bat assembleDebug -Proki.modelId=gemma-3n-e2b
& $adb push "$env:USERPROFILE\Downloads\gemma-3n-E2B-it-int4.litertlm" /sdcard/Android/data/io.github.kimgeonwoo718.roki/files/models/
```

Gemma 3n 파일은 [google/gemma-3n-E2B-it-litert-lm](https://huggingface.co/google/gemma-3n-E2B-it-litert-lm)에서 로그인하고 라이선스에 동의한 뒤 받습니다.
받은 파일은 PowerShell로 확인할 수 있습니다:
```powershell
Get-FileHash .\gemma-3n-E2B-it-int4.litertlm -Algorithm SHA256
# 2ED7BC3A0026C93D5B8A4544B352D9D00CD66FF0BAC3EF6A20AC3D2CBA4010D6
```

## 동작 방식

- **모델 로딩:** 앱 실행 후 한 번만 불러오고 계속 재사용합니다. GPU로 먼저 시도하고, 실패하면 CPU로 자동 전환합니다.
  GPU 초기화 중 앱이 강제 종료되면(네이티브 오류) 다음 실행 때 CPU로 실행하고 화면에 알려줍니다.
- **생성:** 모든 추론은 백그라운드 스레드에서 실행되고, 앱 전체 범위에서 돌아가서 화면 회전에도 끊기지 않습니다.
- **오류 안내:** 모델 파일 없음, 손상, 메모리 부족, 로딩 실패를 한국어로 안내하고 앱은 종료되지 않습니다.

## 개인정보

- `INTERNET` 권한은 모델 다운로드에만 씁니다. 대화와 추론 코드는 네트워크를 쓰지 않습니다.
- 안드로이드 자동 백업을 꺼서(`allowBackup=false`, `data_extraction_rules.xml`) 대화 기록이 구글 드라이브로 올라가지 않습니다.
- 앱 코드에는 어떤 토큰이나 비밀값도 들어 있지 않습니다.

## 라이선스 고지

- Gemma 4 E2B 모델: Apache License 2.0 ([litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm))
- Gemma 3n 모델(선택): [Gemma Terms of Use](https://ai.google.dev/gemma/terms)
- LiteRT-LM: Apache License 2.0
