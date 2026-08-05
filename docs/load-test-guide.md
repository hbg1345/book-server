# 부하 테스트 실행 가이드 (Gatling)

> **도구 선정 근거:** [load-test-tool-comparison.md](load-test-tool-comparison.md)
> **시뮬레이션 위치:** `src/gatling/java/com/example/bookserver/loadtest/`
> **기본 대상:** Cloud Run 배포본 `https://book-server-912285810536.asia-northeast3.run.app`

---

## 1. 빠른 시작

```bash
# 스모크 런 — 스크립트가 도는지만 확인 (2 users, 15초)
./gradlew gatlingRun --simulation=com.example.bookserver.loadtest.LoadSimulation \
    -Pusers=2 -PrampUp=1 -Pduration=15
```

끝나면 콘솔 마지막 줄에 리포트 경로가 찍힙니다:

```
build/reports/gatling/loadsimulation-<타임스탬프>/index.html
```

브라우저로 열면 응답시간 분포·백분위수·시간대별 그래프가 나옵니다. 이 HTML이 Gatling을 고른 이유입니다.

> `build/`는 gitignore 대상이라 리포트는 커밋되지 않습니다. 남기고 싶은 결과는 따로 복사하세요.

---

## 2. 다섯 가지 프로파일

시나리오(`GET /api/books/{uuid}` 조회)는 다섯 개가 공유하고, **부하의 모양만 다릅니다.**

| 시뮬레이션 | 유형 | 기본값 | 기본 소요시간 |
|---|---|---|---|
| `BreakPointSimulation` | 중단점 | 1 → 500 users 증가, 대기시간 없음 | **2분** |
| `LoadSimulation` | 부하 | 50 users 유지 | 11분 |
| `StressSimulation` | 스트레스 | 200 users → 50으로 복귀 | 17분 |
| `SpikeSimulation` | 최고부하 | 20 users + 400 순간 투입 | 8분 |
| `EnduranceSimulation` | 지속성 | 20 users 유지 | **8시간** |

### 실행 순서는 BreakPoint가 먼저입니다

```bash
# 1. 한계점부터 찾는다 — 나머지 프로파일의 숫자가 여기서 나온다
./gradlew gatlingRun --simulation=com.example.bookserver.loadtest.BreakPointSimulation

# 2. 한계의 절반쯤으로 평상시 부하를 확인
./gradlew gatlingRun --simulation=com.example.bookserver.loadtest.LoadSimulation -Pusers=<한계/2>

# 3. 한계의 3~5배로 밀어붙이고 복구되는지 본다
./gradlew gatlingRun --simulation=com.example.bookserver.loadtest.StressSimulation \
    -Pusers=<한계x4> -PbaselineUsers=<한계/2>

# 4. 순간 폭증에서 살아남는지
./gradlew gatlingRun --simulation=com.example.bookserver.loadtest.SpikeSimulation \
    -PbaselineUsers=<한계/2> -PspikeUsers=<한계x8>

# 5. 마지막에 8시간 소크 (누수 확인)
./gradlew gatlingRun --simulation=com.example.bookserver.loadtest.EnduranceSimulation
```

**한계점을 모르는 상태에서 Load 테스트를 먼저 돌리면 "50 users"라는 숫자에 근거가 없습니다.**
기본값 50은 자리를 채워둔 값이지 측정 결과가 아닙니다.

---

## 3. 파라미터

모두 `-P` 옵션으로 넘깁니다. 시간 단위는 **초**입니다.

| 파라미터 | 의미 | 적용 대상 |
|---|---|---|
| `baseUrl` | 대상 서버 | 전체 |
| `users` | 동시 사용자 수 | Load, Endurance, Stress |
| `maxUsers` | 증가 상한 | BreakPoint |
| `baselineUsers` | 평상시 사용자 수 | Stress(복구 구간), Spike |
| `spikeUsers` | 순간 투입 사용자 수 | Spike |
| `duration` | 유지 시간 | 전체 |
| `rampUp` | 목표까지 올리는 시간 | Load, Endurance, Stress |
| `recovery` | 부하를 내린 뒤 관찰 시간 | Stress |
| `beforeSpike` / `spikeHold` / `afterSpike` | 스파이크 전·중·후 | Spike |
| `authorSearchPct` | 저자 검색 비율 % (기본 30) | 전체 |
| `shareConnections` | 커넥션 풀 공유 (기본 true) | 전체 |
| `gatlingHeap` | 부하 생성기 힙 (기본 2g) | 전체 |
| `thinkTimeMin` / `thinkTimeMax` | 요청 간 대기 범위 (기본 1~5초 랜덤) | 전체 |
| `thinkTime` | 대기를 고정값으로 (양끝을 같은 값으로 고정) | 전체 |
| `warmupRequests` | 콜드스타트 예열 횟수 (기본 5) | 전체 |

예시:

```bash
# 로컬 스택 대상으로 스크립트만 검증
./gradlew gatlingRun --simulation=...LoadSimulation \
    -PbaseUrl=http://localhost:8080 -Pusers=5 -Pduration=60

# 지속성 테스트를 1시간으로 축소
./gradlew gatlingRun --simulation=...EnduranceSimulation -Pduration=3600

# 순수 처리량 한계 측정 (사용자 대기시간 제거)
./gradlew gatlingRun --simulation=...BreakPointSimulation -PthinkTime=0

# 열람 시간을 더 길게 (더 현실적, 사용자당 부하는 줄어듦)
./gradlew gatlingRun --simulation=...LoadSimulation -PthinkTimeMin=3 -PthinkTimeMax=10
```

### think time과 사용자 수의 관계

대기 시간이 길수록 **같은 사용자 수가 만드는 부하는 줄어듭니다.** 사용자 1명은 평균
`(응답시간 + 평균 대기)`마다 요청 1건을 보내므로:

| 설정 | 평균 대기 | 사용자 1000명일 때 |
|---|---|---|
| `thinkTime=0` | 0초 | 초당 ~13,000건 |
| `thinkTime=1` | 1초 | 초당 ~930건 |
| **기본값 (1~5초 랜덤)** | **3초** | **초당 ~325건** |
| `thinkTimeMin=3 thinkTimeMax=10` | 6.5초 | 초당 ~150건 |

그래서 대기 시간을 바꾸면 `maxUsers`·`users`도 같이 조정해야 비슷한 부하가 됩니다.

---

## 4. 결과 읽는 법

### BreakPoint — 한계점은 요약표가 아니라 그래프에 있습니다

응답시간 그래프에서 **선이 평평하다가 위로 꺾이는 지점**이 한계입니다. 에러는 그보다
나중에 나타나므로 에러율만 보면 한계를 놓칩니다. 꺾인 시각을 램프 구간과 대조해서
사용자 수로 환산하세요 (기본값 기준: 120초 동안 1→500명이므로 `사용자 수 ≈ 경과초 × 4.2`).

BreakPoint만 **대기시간이 0**이라 사용자 수 = 동시 요청 수입니다. 다른 프로파일과 달리
"동시 접속자"가 아니라 **동시 요청**을 세는 겁니다.

다만 **보고할 숫자는 사용자 수보다 처리량(req/s)이 낫습니다.** 사용자 수는 think time을 몇 초로
잡았느냐에 따라 달라지는 값이라 그 전제 없이는 비교가 안 됩니다. 요청 상세 페이지의
**Response Time against Global Throughput** 산점도에서 점들이 위로 흩어지기 시작하는 처리량이
전제 없는 결론입니다.

### Stress — 봐야 할 건 피크가 아니라 **복구 구간**입니다

부하를 내린 뒤 응답시간이 원래대로 돌아오는지 확인합니다. 과부하는 견뎠는데 이후에도
계속 느리다면(커넥션 풀 고갈, 큐 적체, GC 스래싱) **실패한 테스트**입니다.
피크에서 끝나는 테스트였다면 합격으로 잘못 읽혔을 상황입니다.

### Spike — baseline 그룹을 보세요

리포트에 `baseline`과 `spike` 두 그룹이 분리돼 나옵니다. 중요한 건 **폭증 중에 평범한
사용자가 어떤 경험을 했는가**, 그리고 **폭증이 끝난 뒤 얼마 만에 정상으로 돌아왔는가**입니다.
그 회복 시간이 이 테스트가 만들어내는 숫자입니다.

### Endurance — Gatling 리포트에는 답이 없습니다

누수는 보통 마지막 순간까지 처리량이 멀쩡하다가 터집니다. **서버 쪽 지표를 보세요:**

- Cloud Run 콘솔 → 컨테이너 메모리 사용량이 우상향으로만 가는가
- Cloud SQL → 활성 커넥션 수가 계속 늘어나는가

모든 요청이 200이어도 메모리 곡선이 계속 올라가면 그게 결과입니다.

---

## 5. 함정

### 윈도우 포트 고갈 (`Address already in use`)

```
io.netty.channel.AbstractChannel$AnnotatedSocketException: Address already in use
Caused by: java.net.BindException: Address already in use
```

**서버가 연결을 거부한 게 아니라, 내 노트북이 로컬 포트를 다 쓴 것입니다.** 윈도우 동적 포트는
49152번부터 16,384개뿐이고, 닫은 연결도 `TIME_WAIT`로 몇 분간 붙잡혀 있어 바로 재사용이 안 됩니다.

기본 설정(`shareConnections`)이 모든 가상 사용자가 커넥션 풀 하나를 공유하도록 되어 있어
이 문제를 피합니다. TLS 핸드셰이크 비용 자체를 측정하려면 `-PshareConnections=false`로 끄되,
그때는 사용자 수를 크게 낮춰야 합니다.

내 포트 범위 확인:
```
netsh int ipv4 show dynamicport tcp
```

### GC thrashing으로 죽는 경우

```
Gradle build daemon has been stopped: since the JVM garbage collector is thrashing
```

부하 생성기 힙 부족입니다. 서버와 무관합니다. 큰 실행에서는:

```bash
./gradlew gatlingRun --simulation=... -PgatlingHeap=4g
```

Gradle 데몬 쪽은 `gradle.properties`에서 2GB로 올려두었습니다.

### 부하 생성기가 먼저 죽으면 그건 서버 측정이 아닙니다

노트북에서 부하를 주면 트래픽이 가정용 회선을 지나갑니다. VU를 올리다 보면 Cloud Run이
아니라 **회선 대역폭이나 노트북 CPU가 먼저** 한계에 닿습니다.

**판별법:** Gatling은 타임아웃·응답지연을 보고하는데 **Cloud Run 콘솔 지표는 멀쩡하다면**
병목은 부하 생성기 쪽입니다. BreakPoint에서 예상보다 낮은 숫자가 나오면 반드시 이걸 먼저
의심하세요. 같은 리전의 GCP VM에서 재실행하면 해소됩니다.

### 로컬 측정값은 서버 성능이 아닙니다

`docker compose`로 띄우면 부하 생성기·앱·PostgreSQL이 같은 CPU를 나눠 씁니다. 병목이 섞여서
절대 수치로 쓸 수 없습니다. 로컬은 **스크립트 검증용**입니다.

### 콜드스타트

Cloud Run은 유휴 시 인스턴스를 0으로 내립니다. 모든 시뮬레이션이 시작 전 `warmUp()`으로
5회 예열하고, 이 요청들은 **Gatling이 아닌 순수 JDK HTTP로** 보내므로 통계에 섞이지 않습니다.
콜드스타트 자체를 측정하려면 `-PwarmupRequests=0`으로 끄고 별도로 돌리세요.

### 시나리오는 두 엔드포인트를 섞습니다

기본 비율은 **도서 단건 조회 70% / 저자 검색 30%**입니다.

| 엔드포인트 | 성격 | 무부하 응답시간 |
|---|---|---|
| `GET /api/books/{uuid}` | PK 인덱스 단건 조회 | 79ms |
| `GET /api/authors?name=` | **인덱스 없는 순차 스캔** (저자 71,081행) | 87ms |

`AuthorMapper.findByName`이 `author_name`으로 필터하는데 이 컬럼에 인덱스가 없습니다
(V1은 PK만, V2는 타입만 TEXT로 변경). 매번 전체 스캔입니다.

**무부하에서는 8ms 차이밖에 안 납니다** — 테이블이 6~8MB라 캐시에 다 올라가고, DB가 8 vCPU라
이 정도 스캔은 소화합니다. 격차는 동시 요청이 쌓일 때 벌어지고, 그걸 보는 게 이 혼합의 목적입니다.

가장 싼 엔드포인트만 때리면 그 엔드포인트의 한계가 서버의 한계로 잘못 보고됩니다.

**튜닝 전/후 비교 자료로 쓰기 좋습니다:** 지금 상태로 한 번 돌리고,
`CREATE INDEX idx_author_name ON author (author_name);`를 넣은 뒤 다시 돌리면 차이가 그대로 숫자로 나옵니다.

### `GET /api/books`는 대상이 아닙니다

이 엔드포인트는 페이지네이션이 없고 카탈로그가 10만 권이라, 한 번 호출하면 전체를 직렬화합니다.
부하를 주면 몇 초 만에 OOM으로 죽습니다. 이건 성능 특성이 아니라 **고쳐야 할 결함**이고,
부하 테스트와 별개 작업입니다. 그래서 시나리오는 단건 조회(`/api/books/{uuid}`)를 씁니다.

### 비용

Cloud Run과 Cloud SQL은 실제로 과금됩니다. 특히 8시간 소크와 BreakPoint의 고부하 구간이
비쌉니다. 프로파일을 조정할 때는 짧게 돌려 확인하고, 전체 길이는 확신이 선 뒤에 돌리세요.

---

## 6. CI와의 관계

**Gatling은 CI에 연결돼 있지 않습니다.** `./gradlew build`의 태스크 그래프에 `gatlingRun`이
들어가지 않고, CI가 실행하는 `asciidoctor openapi3`와도 무관합니다.

의도한 설계입니다 — 배포본에 실제 트래픽을 쏘는 작업이므로 빌드의 부수효과가 아니라
**사람이 의식적으로 실행하는 것**이어야 합니다.

`LoadSimulation`과 `EnduranceSimulation`에는 assertion이 걸려 있어(성공률 > 99%, p95 < 1초)
실패 시 종료 코드가 0이 아닙니다. 나중에 성능 회귀 게이트로 쓰고 싶다면 이 둘을 쓰면 됩니다.
Stress·Spike·BreakPoint에는 assertion이 없습니다 — **실패가 곧 관측 대상**이라 성공률을
단언하면 설계상 매번 실패합니다.

---

## 7. 파일 구성

```
src/gatling/
├── java/com/example/bookserver/loadtest/
│   ├── LoadTestConfig.java        # 파라미터 읽기 (-D 시스템 프로퍼티)
│   ├── BookCatalog.java           # 공유 시나리오 + HTTP 설정 + 예열
│   ├── LoadSimulation.java
│   ├── EnduranceSimulation.java
│   ├── StressSimulation.java
│   ├── SpikeSimulation.java
│   └── BreakPointSimulation.java
└── resources/data/
    └── book_uuids.csv             # V3 시드에서 뽑은 2000개 uuid
```

`book_uuids.csv`는 카탈로그 시드에서 샘플링한 것이라 **배포본 DB에 실제로 존재하는 행**을
가리킵니다. 시드를 다시 만들면 이 파일도 다시 뽑아야 합니다:

```bash
{ echo "bookUuid"; gzip -dc src/main/resources/db/seed/books.csv.gz \
    | awk -F, 'NR>1 && NR%50==2 {print $1}' | head -2000; } \
    > src/gatling/resources/data/book_uuids.csv
```

피더는 `random()`입니다. `circular()`로 하면 모든 사용자가 같은 순서로 같은 키를 훑어서
PostgreSQL 캐시가 한 페이지에만 집중되고, 실제 접근 패턴에서는 나오지 않을 낮은 지연시간이
측정됩니다.
