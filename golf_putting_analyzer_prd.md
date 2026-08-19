# [PRD] 스마트폰 카메라 & OpenCV 기반 골프 퍼팅 분석 안드로이드 앱
**Golf Putting Analyzer App Specification**

---

## 1. 프로젝트 개요 및 목표

* **프로젝트명:** Golf Putting Analyzer (가칭)
* **목표:** 스마트폰 고속 카메라(Camera2 API)와 OpenCV 비전 라이브러리를 활용하여 골프 퍼팅의 **거리, 페이스 각도(열림/닫힘), 볼 속도, 궤적**을 정밀 분석하고, 이를 게임화(Gamification) 요소 및 랭킹 시스템과 결합하여 상용화 가능한 수준의 안드로이드 앱으로 구축합니다.
* **주요 특징:**
  * 별도의 외산 센서 장비 없이 스마트폰 단독으로 정밀 퍼팅 분석 수행
  * 측면 사선 거치 환경을 위한 원근 보정(Perspective Transform) 알고리즘 적용
  * 공/매트 색상 및 빛 반사 사전 학습(Calibration Wizard)을 통한 비전 검출 정확도 극대화
  * 대형 HUD UI 및 TTS 음성 안내를 통한 직관적인 피드백 제공

---

## 2. 기존 코어 모듈 및 구현 상태

앱의 핵심 비전 처리 및 카메라 세션은 아래 3개 주요 파일/컨트롤러 기반으로 구축되어 있습니다.

1. **`CameraScreen.kt` (UI 및 모션 감지 상태 머신):**
   * Jetpack Compose + `AndroidView(TextureView)` 기반 프리뷰 연동
   * `PuttingState` (`SETUP` ➔ `WAITING` ➔ `STABILIZING` ➔ `READY` ➔ `PUTTING`) 자동 상태 관리
   * 볼 영역 픽셀 밝기(Brightness) 변화 모니터링을 통한 녹화 자동 Trigger
2. **`VideoAnalyzer.kt` (MediaCodec 디코딩 & OpenCV 분석 엔진):**
   * `MediaExtractor` / `MediaCodec` 하드웨어 가속 기반 프레임 단위 디코딩
   * dynamic ratio 기반 ROI 설정 및 `Imgproc.threshold`, `findContours`, `Core.absdiff` 기반 Gate A/B 통과 시점(X좌표, 타임스탬프) 추출
   * 분석 결과 오버레이 정지화면 이미지 생성
3. **`CameraController.kt` & `HighSpeedConfig.kt` (Camera2 제어):**
   * `CameraConstrainedHighSpeedCaptureSession` (60fps~240fps) + `MediaRecorder` 고속 영상 저장
   * `HandlerThread` 기반 백그라운드 하드웨어 제어
   * Coroutine `suspendCoroutine` 활용 비동기 하드웨어 생명주기 및 리소스 동기화

---

## 3. 정보 아키텍처 및 메뉴 구성 (Information Architecture)

앱은 화면 이동을 최소화하고 퍼팅 연습 동선에 최적화된 **4개 메인 탭(Bottom Navigation)**으로 구성됩니다.


```

[Main Bottom Navigation]
├── [Tab 1] 연습 (Practice)
│    ├── 자유 연습 모드 (Free Practice)
│    └── 챌린지 모드 (Distance Challenge, Continuous Success Challenge)
│
├── [Tab 2] 리포트 (Analytics)
│    ├── 일자별 / 거리별 퍼팅 성공률 및 페이스 각도 편차 그래프
│    └── 샷별 프레임 분석 오버레이 정지화면 다시보기
│
├── [Tab 3] 랭킹 (Leaderboard)
│    ├── 주간 / 월간 정밀도 랭킹 리더보드 (Firebase DB 연동)
│    └── 티어 시스템 (브론즈 ➔ 실버 ➔ 골드 ➔ 플래티넘 ➔ 마스터)
│
└── [Tab 4] 설정 & 캘리브레이션 (Settings & Calibration)
├── 매트 & 공 색상 사전 학습 Wizard
└── 음성 안내(TTS) / 단위 / 카메라 모드 / 디버그 설정

```

---

## 4. 카메라 거치 및 사용자 UX 가이드

### 4.1 카메라 거치 가이드
* **권장 설치 위치:** 퍼팅 라인 **측면(볼 진행 방향 옆) 0.5m~1m 거리, 높이 50~80cm**
* **거치 방식:** 일반 스마트폰 삼각대로 아래쪽을 대각선(45도)으로 내려다보도록 세팅
* **원근 보정 (Perspective Transform):**
  * 측면 대각선 앵글로 인해 발생하는 좌표 왜곡을 OpenCV Homography/Perspective Transformation을 적용해 평면 좌표계로 전환 ➔ Pixel-to-Cm 변환 오차 최소화

### 4.2 퍼팅 세션 중 피드백 UX
* **대형 HUD 오버레이:**
  * 카메라 프리뷰 전면에 투명도가 적용된 대형 텍스트/그래픽 오버레이 배치 (3~4m 거리에서도 시인성 확보)
* **퍼팅 직후 3초 간 액션:**
  1. **대형 피드백 팝업:** `[실제 거리: 3.2m]`, `[페이스 각도: +1.2° Open]` 화면 중앙 팝업
  2. **음성 피드백 (TTS):** *"3.2미터, 열림 1.2도"* 음성 자동 안내
  3. **분석 정지화면 팝업:** OpenCV 트래킹 궤적(Red/Green Line) 및 공 임팩트 프레임 오버레이 스크린샷 3초간 표시 후 자동 `READY` 대기 상태 전환

---

## 5. 데이터베이스 및 랭킹 설계 (Firebase & Local DB)

### 5.1 Firestore 데이터 구조 (Cloud Remote DB)

```

firestore-root
├── users (컬렉션)
│    └── {userId} (문서)
│         ├── nickname: String
│         ├── handicap: Int
│         ├── rankPoints: Int
│         └── stats: { totalPutts: Int, avgDistanceErrCm: Float, avgAngleErrDeg: Float }
│
├── practice_sessions (컬렉션)
│    └── {sessionId} (문서)
│         ├── userId: String
│         ├── mode: String ("FREE", "CHALLENGE")
│         ├── createdAt: Timestamp
│         └── summary: { totalShots: Int, successRate: Float }
│
├── shot_records (컬렉션)
│    └── {shotId} (문서)
│         ├── userId: String
│         ├── sessionId: String
│         ├── timestamp: Timestamp
│         ├── targetDistanceCm: Float
│         ├── actualDistanceCm: Float
│         ├── faceAngleDeg: Float
│         ├── ballSpeedMps: Float
│         ├── isSuccess: Boolean
│         └── debugImageUrl: String?
│
└── leaderboards (컬렉션)
└── {weeklyKey} (문서)
└── rankings: Map<String, UserRankDTO>

```

### 5.2 랭킹 점수 산출 알고리즘
$$\text{Shot Score} = 100 - (\vert{}\text{목표거리} - \text{실제거리}\vert{}_{\text{cm}} \times 2) - (\vert{}\text{페이스각도}_{\text{deg}}\vert{} \times 10)$$
* 거리 오차 및 각도 오차를 합산하여 100점 만점 기준으로 환산 후 누적 랭킹 포인트 계산.

---

## 6. 비전 정확도를 위한 캘리브레이션 Wizard UX

1. **매트 규격 설정 & 프리셋:**
   * Gate A - Gate B 간격(`realDistanceCm`) 입력 및 장소별 프리셋 저장 지원
2. **매트 빛 반사 사전 학습 (Background Scan):**
   * 공이 없는 상태에서 매트를 스캔하여 빛 반사광 영역 및 백그라운드 모델 등록
3. **공 색상(HSV) 및 외곽선 학습:**
   * 공을 지정 위치에 놓은 후 HSV 색상 범위 및 원형 윤곽선(`findContours`/`HoughCircles`) 추출
   * 검출된 공의 외곽선에 **초록색 하이라이트 링**을 프리뷰 상에 오버레이
   * **[공 인식 확인 / 재시도]** 승인 팝업으로 사용자가 검증

---

## 7. 프로젝트 패키지 및 폴더 구조 (Clean Architecture)

```text
com.example.golfputting/
│
├── core/                        # 카메라 & 비전 처리 코어
│   ├── camera/
│   │   ├── CameraController.kt  # [기존] 하드웨어 레코딩 제어
│   │   └── HighSpeedConfig.kt   # [기존] 고속 세션 설정
│   ├── vision/
│   │   ├── VideoAnalyzer.kt     # [기존] 디코딩 & OpenCV 분석
│   │   └── PerspectiveTransformer.kt # 측면 앵글 원근 보정
│   └── util/
│       └── TtsManager.kt        # 음성 피드백 유틸
│
├── data/                        # Data Layer
│   ├── model/                   # DTO (UserProfile, ShotRecord 등)
│   ├── local/                   # Room Local DB (ShotEntity, ShotDao)
│   ├── remote/                  # Firebase Auth & Firestore Service
│   └── repository/              # Data Repositories
│
├── ui/                          # Presentation Layer (Jetpack Compose)
│   ├── navigation/              # NavHost, Routes
│   ├── theme/                   # Typography, Color
│   ├── components/              # LargeHudOverlay, Dialogs
│   └── screens/
│       ├── auth/                # LoginScreen, LoginViewModel
│       ├── practice/            # CameraScreen, PracticeViewModel, ChallengeScreen
│       ├── analytics/           # ReportScreen, ShotDetailDialog
│       ├── leaderboard/         # LeaderboardScreen, LeaderboardViewModel
│       ├── calibration/         # CalibrationWizardScreen, CalibrationViewModel
│       └── settings/            # SettingsScreen, SettingsViewModel
│
└── MainActivity.kt              # App Entry Point

```

---

## 8. 데이터 schema 스펙 (Code Definition)

### 8.1 Room Entity (Local Data)

```kotlin
@Entity(tableName = "shot_records")
data class ShotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val targetDistanceCm: Float,
    val actualDistanceCm: Float,
    val faceAngleDeg: Float,
    val ballSpeedMps: Float,
    val isSuccess: Boolean,
    val debugImagePath: String?
)

```

### 8.2 Firestore DTOs (Remote Data)

```kotlin
data class UserProfile(
    val uid: String = "",
    val nickname: String = "",
    val handicap: Int = 18,
    val rankPoints: Int = 1000,
    val totalPutts: Int = 0,
    val avgDistanceErrCm: Float = 0f,
    val avgAngleErrDeg: Float = 0f
)

data class ShotRecord(
    val shotId: String = "",
    val userId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val targetDistanceCm: Float = 0f,
    val actualDistanceCm: Float = 0f,
    val faceAngleDeg: Float = 0f,
    val ballSpeedMps: Float = 0f,
    val isSuccess: Boolean = false,
    val debugImageUrl: String? = null
)

```

---

## 9. 단계별 개발 실행 로드맵 (Roadmap)

1. **Phase 1: 패키지 구조 및 Navigation 뼈대 구축**
* 프로젝트 내 폴더 구조 세팅 및 `NavHost` 기반 4개 탭 화면 전환 연결


2. **Phase 2: 캘리브레이션 Wizard 모듈 개발 (`CalibrationWizardScreen.kt`)**
* 공/매트 색상 학습, 빛 반사 마스킹, 외곽선 초록 링 시각화 구현


3. **Phase 3: 대형 HUD UI 및 TTS 피드백 바인딩**
* `CameraScreen.kt` 연동 대형 팝업 UI 개발 및 `TtsManager` 음성 피드백 연동


4. **Phase 4: Firebase 로그인 및 DB 백엔드 연동**
* Google 로그인, Firestore `ShotRecord` 저장 및 Room 오프라인 캐싱 처리


5. **Phase 5: 리포트 UI 및 주간 리더보드 구현**
* 통계 차트 그래프 화면 개발 및 주간 스코어 랭킹 탭 연동



```

```