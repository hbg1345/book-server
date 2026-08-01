# 클라우드 플랫폼 비교: book-server 배포 (GCP · AWS · Azure · IBM Cloud)

> **대상 워크로드:** Spring Boot(JVM) + PostgreSQL 컨테이너 앱 (`feature/dockerize`)
> **규모 가정:** "성장하는 서비스" — 수만~수십만 명 목표, 꾸준한 트래픽, 확장성·안정성 중요
> **조사 시점:** 2026년 (가격·무료 티어는 수시로 바뀌므로 배포 직전 각 콘솔에서 재확인 필요)

---

## 0. 한눈에 보기 (TL;DR)

| 항목 | GCP | AWS | Azure | IBM Cloud |
|---|---|---|---|---|
| **서버리스 컨테이너** | Cloud Run | ECS Express Mode ※App Runner 신규 종료 | Container Apps (ACA) | Code Engine |
| **Scale-to-zero** | ✅ 기본 | ✅ 지원(min=0), 단 ALB 고정비 | ✅ 지원 | ✅ 지원 |
| **관리형 Postgres** | Cloud SQL | RDS / Aurora | PG Flexible Server | Databases for PostgreSQL |
| **DB 최소 월 비용** | ~$8 (f1-micro) | ~$12 (t4g.micro) | ~$12 (B1ms) | **~$82 (HA 2노드 강제)** |
| **신규 크레딧** | **$300 / 90일** | $200 / ~6개월 | $200 / 30일 | $200 / 30일 |
| **12개월 무료 DB** | ❌ | 레거시 계정만 | ✅ B1ms 750h/월 | ❌ |
| **글로벌 리전 수** | ~40+ | **~36 (최다 성숙도)** | **60+** | ~10-11 (최소) |
| **생태계·취업 가치** | 높음 | **최고** | 높음 | 낮음 |
| **소규모 시작 난이도** | **가장 쉬움** | 중간 | 중간 | 쉬움 |
| **예상 월 비용(소규모)** | **~$15-60** | ~$50-65 (ALB 고정비) | ~$30-50 | **~$85-185** |

**결론 요약:** 이 워크로드(단일 Spring Boot 이미지 + 관리형 Postgres, 소규모 시작→성장)에는
**GCP Cloud Run이 비용·배포 단순성에서 1순위**, **AWS가 생태계·확장성·취업가치에서 1순위**입니다.
Azure는 Microsoft 생태계·엔터프라이즈에 강하고, IBM Cloud는 이 규모에선 비용·생태계 모두 불리합니다.

---

## 1. GCP (Google Cloud Platform)

### 컨테이너 서비스 — Cloud Run
- 이미지만 주면 HTTPS URL이 나오는 **가장 단순한 서버리스 컨테이너**. `gcloud run deploy` 한 줄.
- **Scale-to-zero 기본** — 유휴 시 0개, 비용 0.
- 자동확장: 요청 동시성 기반. 인스턴스당 **동시 요청 1000개**까지 → 인스턴스 적게 뜨고도 많은 유저 처리.
- **콜드스타트**가 JVM의 약점(수초). 완화: `--min-instances=1`(1개 상시 예열) 또는 GraalVM 네이티브/CDS.

### 관리형 DB — Cloud SQL for PostgreSQL
- 표준 관리형 Postgres. **공식 Cloud SQL Java 커넥터 + IAM 인증**이 Spring Data와 깔끔하게 통합.
- 주의: 최소 사양(db-f1-micro/g1-small)은 **SLA 미적용** → 실서비스는 dedicated 인스턴스로 승급 필요.
- 더 큰 성능이 필요하면 AlloyDB(단, 최소 ~$250/월로 지금은 과함).

### 가격
- Cloud Run: vCPU $0.000024/s, 메모리 $0.0000025/GiB-s, 요청 $0.40/백만. **항상 무료**: 180k vCPU-s + 360k GiB-s + 200만 요청/월.
- Cloud SQL: f1-micro ~$8, g1-small ~$26, standard-1 ~$49 (+스토리지·백업 별도). **Cloud SQL은 무료 티어 없음.**
- 신규: **$300 크레딧 / 90일**.

### 장점
- **big 3 중 가장 쉬운 컨테이너 배포**, GitHub Actions CI/CD 궁합 좋음.
- scale-to-zero + 넉넉한 무료 티어 + $300 → 개발·초기 사실상 무료.
- 표준 OCI 컨테이너 + 표준 Postgres라 **이식성이 상대적으로 높음**.

### 단점
- JVM 콜드스타트(min-instances 또는 네이티브로 완화).
- 서버리스 수평 확장 시 **Postgres 커넥션 고갈** 주의 → 커넥션 풀링 필수.
- 최소 사양 Cloud SQL은 SLA 없음.

### 소규모 예상 비용
컨테이너(min=1, 1vCPU/512MB) ~$10-25 + Cloud SQL(f1→g1-small) $8-26 + 스토리지/백업 몇 $
→ **월 ~$15(최소) ~ $40-60(현실적)**. 첫 90일은 $300 크레딧으로 사실상 $0.

---

## 2. AWS (Amazon Web Services)

### ⚠️ 중요 변경: App Runner 신규 고객 종료 (2026)
- AWS가 **App Runner를 2026.4.30부터 신규 고객에게 닫음**(availability update 2026.3.31 공지). 기존 서비스는 계속 운영·신규 리소스 생성 가능하나 **신규 기능 없음(유지보수 모드)**.
- **공식 대체재 = Amazon ECS Express Mode** (re:Invent 2025.11.21 출시). → **지금 새로 시작하면 App Runner는 선택지 아님.**

### 컨테이너 서비스 — ECS Express Mode → ECS/Fargate → EKS
- **ECS Express Mode**: ECS 안의 신기능. 이미지 + IAM 롤 2개 주면 **ALB·TLS·오토스케일·VPC·보안그룹을 자동 생성**하고 `*.ecs.<region>.on.aws` HTTPS URL 제공. App Runner의 간편함을 계승하되, 만든 리소스가 **내 계정에 그대로 남아** 나중에 풀 ECS로 "졸업" 가능(마이그레이션 없이).
  - **Scale-to-zero 지원**(`minTaskCount:0`) — 단 **ALB는 0태스크에도 계속 과금**(~$16+/월). Fargate 컴퓨트만 0으로.
  - 콜드스타트: CloudWatch 메트릭 기반 스케일아웃이라 **Cloud Run보다 느림**(요청 게이트가 아님).
  - 이미지 전용(소스 빌드 모드 없음 → ECR에 직접 push, 공식 GitHub Action 제공). 커스텀 도메인은 ACM 인증서 직접 붙여야.
- **ECS on Fargate**: 표준 "성장한" AWS 컨테이너. Express가 이 위의 간편 계층.
- **EKS**: 관리형 K8s. **컨트롤 플레인만 $73/월** → 단일 앱엔 과함.
- 성장 경로: Express Mode에서 시작 → 같은 리소스 그대로 풀 ECS 제어로 확장(재플랫폼 부담 낮아짐).

### 관리형 DB — RDS / Aurora
- **RDS for PostgreSQL**: 표준 관리형. db.t4g.micro(2 vCPU 버스트/1GB)부터. **소규모 상시 DB엔 가장 싼 바닥.**
- **Aurora Serverless v2**: 2024.11부터 **min 0 ACU + 오토포즈** 지원 → 유휴 시 컴퓨트 $0. 단 상시 트래픽엔 0.5 ACU(~$44/월)+라서 RDS보다 비쌈.

### 가격
- ECS Express Mode: **서비스 자체 요금 없음.** 밑단 리소스만 과금 → Fargate($0.04048/vCPU-h + $0.004445/GB-h) + **ALB(~$16-18/월 고정 + LCU)** + CloudWatch/전송.
  - ALB 하나를 **최대 25개 서비스가 공유** 가능 → 여러 서비스 돌리면 ALB 비용 분산.
- RDS t4g.micro: ~$12-15/월(+gp3 스토리지 $0.115/GB).
- 신규: **2025.7 개편** — $200 크레딧, **6개월 또는 소진 시까지**(기존 12개월 상시 무료 티어는 신규 계정에 미적용).

### 장점
- **시장 점유율 1위(~30%)** → 최대 커뮤니티·자료·서드파티·**취업 가치 최고**.
- ECS Express Mode의 간편함 + 만든 리소스가 내 계정에 남아 **풀 ECS로 무이전 성장**.
- 200+ 서비스로 뭐든 네이티브(큐·캐시·시크릿 등). RDS+Aurora로 소~초대형 커버.
- **RDS와 같은 VPC·보안그룹에 바로 안착** → AWS에 이미 자원이 있으면 DB 연결이 자연스러움.

### 단점
- **big 3 중 학습곡선 가장 가파름**(IAM 롤 2개·VPC·보안그룹·ALB·ACM).
- **ALB 고정비(~$16+/월)가 유휴에도 발생** → 단일 소규모 서비스엔 Cloud Run/ACA보다 바닥비용 높음(서비스 여러 개면 ALB 공유로 상쇄).
- 콜드스타트가 Cloud Run보다 느림(메트릭 기반 스케일).
- **비용 항목이 잘게 쪼개져** 예측 어려움(NAT·egress 폭탄 주의).
- Aurora·ECS 구성은 락인. 이식성 원하면 RDS+일반 컨테이너 유지.

### 소규모 예상 비용
ECS Express(Fargate 0.5vCPU/1GB) ~$18-20 + **ALB ~$16-18** + RDS t4g.micro $12-15 + 스토리지/전송 $4-8
→ **월 ~$50-65**(ALB 고정비가 바닥을 높임). $200 크레딧으로 초기 몇 달 커버.

---

## 3. Azure (Microsoft)

### 컨테이너 서비스 — Container Apps (ACA)
- Cloud Run에 가장 가까운 서버리스 컨테이너(내부는 관리형 K8s + **KEDA** 이벤트 오토스케일).
- **Scale-to-zero 지원**(`minReplicas: 0`).
- **콜드스타트** 0→1에 ~15-30초(JVM은 더). 완화: `minReplicas: 1`(단 유휴 저율 과금 조건 있음).
- ⚠️ **Azure Spring Apps는 은퇴 중**(2025~2028). Java 전용 PaaS는 사실상 사라졌고, **Spring Boot는 그냥 ACA에 컨테이너로 배포** — Cloud Run과 동일 방식.

### 관리형 DB — PostgreSQL Flexible Server
- 현 플래그십(구 Single Server는 폐기). **PgBouncer 내장, Entra 인증, HA SLA**.
- Burstable **B1ms**(1 vCore/2GB)부터. 단 **버스트 크레딧 소진 시 스로틀** → 상시 부하면 조기에 상위 티어로.

### 가격
- ACA: 활성 $0.000024/vCPU-s, 유휴 $0.000003/vCPU-s, 요청 $0.40/백만. **항상 무료**: 180k vCPU-s + 360k GiB-s + 200만 요청/월(구독당).
- PG B1ms: 컴퓨트 ~$12/월 + 스토리지 ~$0.115/GB(백업 100%까지 무료).
- 신규: **$200 / 30일** + **12개월 무료 서비스(B1ms Postgres 750h/월 포함)** → 소규모 DB 1년 사실상 무료.

### 장점
- Cloud Run급 서버리스(scale-to-zero + KEDA) + 넉넉한 무료 grant.
- **Microsoft 생태계**: Entra ID 인증, GitHub Actions/Azure DevOps, 관리 ID로 무비밀번호 DB 접속.
- $200 + 12개월 무료 B1ms로 저렴한 시작.

### 단점
- JVM 콜드스타트(대개 minReplicas:1로 서버리스 이점 일부 포기).
- **Burstable Postgres 스로틀** 함정.
- 가격 페이지가 로그인 전 "$-"로 떠 **비용 추정 투명성 낮음**.
- Entra/Front Door 통합 락인(이미지는 이식 가능).

### 소규모 예상 비용
ACA(0.5vCPU/1GB 상시) ~$15-30 + PG B1ms+스토리지 ~$16 + 요청/egress ~$0-2
→ **월 ~$30-50**. 무료 계정이면 첫 ~12개월 사실상 $0. HA 켜면 DB 컴퓨트 약 2배.

---

## 4. IBM Cloud

### 컨테이너 서비스 — Code Engine
- Cloud Run에 가까운 서버리스 컨테이너. **Scale-to-zero 지원**, 0.125 vCPU부터 세밀.
- ⚠️ **콜드스타트가 큼** — IBM 벤치서 warm 0.22s vs **cold ~17.2s**(JVM은 더). 상시 warm 또는 네이티브 필요.
- 상위: IKS(순정 K8s), **OpenShift(ROKS)** — 하이브리드/규제 산업 강점이나 단일 앱엔 과함.

### 관리형 DB — Databases for PostgreSQL
- **기본이 HA 2노드 클러스터**(데이터 이중화). 최소 0.5 vCPU/4GB/5GB disk.
- 백업 30일 무료, 온라인 리사이즈, 디스크 4TB까지.
- **무료/Lite 티어 없음** — 첫 시간부터 과금.

### 가격
- Code Engine: $0.00003431/vCPU-s + $0.00000356/GB-s + $0.538/백만 요청. **무료 grant**: 100k vCPU-s + 200k GB-s/월.
- PostgreSQL: **최소 구성도 ~$82/월**(HA 2노드 강제가 바닥가를 높임).
- 신규: **$200 / 30일**.

### 장점
- 진짜 scale-to-zero 서버리스 + 월 무료 grant → 유휴 비용 낮음.
- **OpenShift 하이브리드/멀티클라우드·규제 산업**(금융·정부·헬스케어)엔 최상급.

### 단점 (솔직하게)
- **생태계·커뮤니티 최소** — 자료·SO 답변·서드파티·Terraform 예제 부족.
- **콜드스타트 최악(~17s)**.
- **리전 ~10-11개로 big 3보다 훨씬 적음**(글로벌 지연·데이터 레지던시 선택폭 좁음).
- **무료 관리형 Postgres 없음 + HA 강제**로 DB 바닥가 높음(~$82).
- **취업 가치 낮음**(전이 가능한 건 OpenShift/K8s 스킬 정도).

### 소규모 예상 비용
상시 컨테이너(0.5vCPU/1GB) ~$50 + Postgres(HA 최소) ~$82
→ **월 ~$130**(1vCPU/2GB면 ~$185). scale-to-zero 허용 시 컨테이너는 단 몇 $까지 내려가나 **DB $82가 바닥**. 첫 달 $200 크레딧.

---

## 5. 기준별 종합 비교

### 💰 비용 (소규모 시작 기준, 낮을수록 좋음)
1. **GCP** (~$15-60, $300 크레딧) — 최저
2. **Azure** (~$30-50, 12개월 무료 DB가 큼)
3. **AWS** (~$40-70, App Runner 유휴 바닥비용)
4. **IBM** (~$85-185, HA 강제 DB가 발목)

### 📈 확장성 (소→대)
- **AWS**: 절대적 상한 최대(30+ 리전, Aurora, 15 read replica). 단 제품 교체형 성장.
- **GCP**: Cloud Run 플래그 하나로 확장 + 글로벌 LB. DB는 read replica→AlloyDB. 부드러움.
- **Azure**: ACA maxReplicas + KEDA, 60+ 리전, Front Door. 부드러움.
- **IBM**: 규모 자체는 감당하나 리전·생태계가 성장의 천장.

### 🚀 배포 단순성 / 개발자 경험
1. **GCP** (`gcloud run deploy` — 최고)
2. **Azure** (`az containerapp up`) / **IBM** (Code Engine) 비슷
3. **AWS** (App Runner는 쉽지만 VPC-RDS 붙는 순간 복잡)

### 🎓 학습·취업 가치
1. **AWS** (압도적) → 2. **Azure**(엔터프라이즈·MS) ≈ **GCP**(데이터·K8s) → 4. **IBM**(낮음, OpenShift만 예외)

### ❄️ JVM 콜드스타트 대응
- 네 곳 모두 scale-to-zero 시 콜드스타트 존재 → `min-instances=1`(상시 예열) 또는 GraalVM 네이티브로 완화
- **GCP Cloud Run**: 요청 게이트 스케줄러라 콜드스타트가 상대적으로 빠름
- **AWS ECS Express**: CloudWatch 메트릭 기반이라 스케일아웃이 더 느림(단 ALB는 항상 warm)
- **IBM Code Engine**: 콜드스타트 페널티 최악(~17s)

---

## 6. 이 프로젝트(book-server) 관점 추천

| 당신이 가장 중시한다면 | 추천 |
|---|---|
| **비용 최소 + 가장 쉬운 배포** | **GCP Cloud Run + Cloud SQL** |
| **취업·이력 + 최대 생태계 + 장기 확장** | **AWS ECS Express Mode→Fargate + RDS** |
| **Microsoft 생태계 / 12개월 무료 DB** | **Azure Container Apps + PG Flexible** |
| **하이브리드·규제 엔터프라이즈 (지금은 해당 없음)** | IBM Code Engine / OpenShift |

**정리:**
- 지금 단계(Spring Boot 단일 이미지 + Postgres, 소규모 시작 → 성장)에서 **가성비·속도는 GCP Cloud Run**, **커리어·생태계·상한선은 AWS**의 2파전입니다.
- **IBM Cloud는 이 유형·규모에선 비용(HA 강제 DB)과 생태계 모두 불리**하므로 후순위.
- 어느 쪽이든 **앱은 표준 OCI 컨테이너 + 표준 PostgreSQL**로 유지하면 나중에 갈아타기 쉬우니, 처음부터 특정 클라우드 전용 기능(Aurora, Entra 강결합 등)에 과하게 묶이지 않는 게 유리합니다.

> ⚠️ 모든 가격은 2026년 조사 시점 기준 추정이며 리전·트래픽·HA·egress에 따라 달라집니다. 배포 직전 각 클라우드 공식 계산기로 재확인하세요.
