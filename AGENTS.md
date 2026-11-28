# 두밋 백엔드 에이전트 지침

## 실행 명령

저장소 루트에서 Java 21과 Gradle Wrapper를 사용한다.

| 작업 | Windows PowerShell | Bash |
| --- | --- | --- |
| 빌드 | `.\gradlew.bat build` | `./gradlew build` |
| 전체 테스트 | `.\gradlew.bat test` | `./gradlew test` |
| 특정 테스트 | `.\gradlew.bat test --tests 'com.domeet.backend.BackendApplicationTests'` | `./gradlew test --tests 'com.domeet.backend.BackendApplicationTests'` |
| 로컬 실행 | `.\gradlew.bat bootRun` | `./gradlew bootRun` |

`application.yaml`은 기본적으로 로컬 PostgreSQL의 `domeet` DB에 연결한다. 애플리케이션 실행과 컨텍스트 테스트에는 `DOMEET_DB_PASSWORD` 환경변수 또는 지정된 PostgreSQL 연결 설정을 제공한다.

Google 가입 진입을 로컬에서 확인하려면 백엔드 실행 설정에 `DOMEET_GOOGLE_CLIENT_ID`와 `DOMEET_SIGNUP_TOKEN_SECRET`을 설정한다. 가입 토큰 서명 키에는 암호학적으로 무작위인 32바이트 이상을 표준 Base64로 인코딩한 값을 사용한다.

## 작업 원칙

포트원 본인인증 테스트에는 등록한 V2 테스트 채널의 `PORTONE_STORE_ID`, `PORTONE_IDENTITY_VERIFICATION_CHANNEL_KEY`와 서버 전용 `PORTONE_API_SECRET`을 로컬 실행 환경에 제공한다.

개발 환경에서 초안 약관 동의 API를 확인하려면 `DOMEET_DRAFT_TERMS_ENABLED=true`를 설정한다. 초안은 검증용이며 기본 설정에서는 동의를 받지 않는다.

- 요청받지 않은 기능이나 구조를 임의로 추가하지 않는다.
- 코드 수정 전에 관련 구현과 테스트를 확인한다.
- 기능 ID로 기능 명세서, 기술 설계서, ERD를 대조한다. 충돌하는 내용은 사용자에게 확인한다. 화면 상태와 예외는 UI/UX 설계도 확인한다.
- 확정되지 않은 정책이나 API 계약을 임의로 결정하지 않는다. API 계약이나 스키마를 변경하면 관련 문서도 갱신한다.
- 새 라이브러리를 도입하기 전에 Java 21, Spring Boot 4.1.2 및 기존 의존성과의 호환성을 확인한다.
- 비밀번호, 토큰, API 키, OAuth Client Secret, DB 자격 증명은 환경변수나 Git에서 제외된 로컬 설정으로 관리한다. 코드와 Git 추적 파일에 민감정보를 포함하지 않는다.
- 작업 완료 시 변경 파일, 변경 이유, 검증 결과, 남은 문제를 간결하게 보고한다.

## 프로젝트 맥락

두밋은 친구 그룹이 다른 그룹을 탐색하고, 참여 신청과 양쪽 그룹의 투표를 거쳐 공식 그룹 채팅을 시작하는 서비스다. [프로젝트 개요](https://app.notion.com/p/40bf7bfe3aab80daac7ffa06f7146ac5)

- Java 21, Gradle Wrapper, Spring Boot 4.1.2, Spring MVC, Bean Validation, Spring Data JPA, PostgreSQL, Actuator, JUnit Platform

## 협업과 유지보수

- 일반 작업은 `develop`에서 `feat/*`, `fix/*`, `chore/*`로 분기하고 리뷰를 거친 PR로 `develop`에 병합한다. 배포는 `develop → main` PR을 사용한다. 긴급 수정은 `main`에서 `hotfix/*`로 분기한 뒤 `develop`에도 반영한다. `main`과 `develop`에 직접 푸시하지 않는다.
- 커밋은 `type: 한국어 변경 내용` 형식을 사용한다. 유형은 `feat`, `fix`, `refactor`, `docs`, `test`, `chore`다. PR에 변경 사항, 실제 테스트 결과, 리뷰 요청 사항을 적는다. [백엔드 개발 협업 규칙](https://app.notion.com/p/419f7bfe3aab81048938c5b187f2bc2a), [브랜치 · Merge 전략](https://app.notion.com/p/418f7bfe3aab8127a7b6c44d07ad312d)
- 코드, 빌드 설정, 확정 명세와 이 지침이 어긋나면 근거와 함께 지침 갱신을 제안한다.

## Java 백엔드 구현

이 범위는 Spring MVC 요청을 처리하고 서비스의 상태 전이와 Spring Data JPA 영속화를 연결한다. 테스트는 대응하는 `src/test/java` 패키지에 작성한다.

- `auth`, `member`, `group`, `matching`, `chat` 등 도메인 중심으로 구현한다. 패키지는 소문자, 클래스는 PascalCase, 메서드·변수는 camelCase, 상수는 UPPER_SNAKE_CASE를 사용한다.
- Controller는 요청·응답, Service는 비즈니스 규칙과 트랜잭션, Repository는 DB 접근을 맡는다. 요청과 응답 DTO를 분리하고 JPA 엔티티의 직접 반환은 피한다. API는 `/api/v1/groups`처럼 소문자 복수형 리소스를 사용한다.
- 공통 코드는 실제 공유 지점이 생겼을 때 `global`로 옮긴다. 패키지나 추상 계층은 구현 시 추가한다. [백엔드 개발 협업 규칙](https://app.notion.com/p/419f7bfe3aab81048938c5b187f2bc2a)

### 도메인 규칙

- 접근 권한과 계정 이용 상태는 서버에서 확인한다.
- 매칭은 양쪽 그룹에서 각각 유효 투표자의 **절반 이상**이 찬성하면 승인한다. 그룹별 유효 투표자 수를 `n`으로 두고 필요한 찬성 수를 `ceil(n / 2)`로 계산한다. 양쪽 그룹 모두 유효 투표자가 최소 2명이어야 한다. 이 기준은 프로젝트 개요보다 MATCH-006 상세 명세를 우선 적용한다. [MATCH-006 상세 명세](https://app.notion.com/p/40bf7bfe3aab817ebdd1fd8453ef35cf)
- 인증은 카카오, Google, Apple의 인증 결과를 서버에서 검증하고 기기별 세션을 관리한다. 최초 가입의 만 19세 이상 휴대폰 본인확인, 토큰 갱신과 폐기, 제재 중 접근 범위는 AUTH 및 ADMIN 설계를 확인한다. [기술 설계서](https://app.notion.com/p/414f7bfe3aab8188bcbcf81a855142d3)
- 그룹 생성 시 우리 그룹 채팅방과 그룹장 참여 기록을 함께 생성한다. 그룹 생성·가입·그룹장 위임 수락에서 회원당 소속 5개와 그룹장 3개 한도를 검증한다. 정식 그룹장과 임시 그룹장 권한을 구분한다. [GROUP-001](https://app.notion.com/p/415f7bfe3aab8175a2dfe4e88f3d7581), [ERD 최종 검수](https://app.notion.com/p/416f7bfe3aab81e1b2cee73488614daa)
- A–B와 B–A는 같은 그룹 쌍이다. 진행 요청 중복을 방지하고, 자격 변경 시 유효표와 기준을 함께 갱신한다. 성공 전이, 공식 채팅방 생성, 결과 알림은 동시 요청이나 재시도에도 한 번만 반영한다. 만료·인원 부족으로 실패한 경우의 7일 재신청 제한과 성공 후 채팅방 종료를 구별한다. 투표자의 개별 선택은 공개하지 않으며 종료 시 삭제한다. [MATCH-001](https://app.notion.com/p/415f7bfe3aab8195803ef6461981d81d), [MATCH-007](https://app.notion.com/p/415f7bfe3aab8179bd04c910cb661f39), [MATCH-008](https://app.notion.com/p/415f7bfe3aab81688771cab150890256)
- 채팅의 전송·조회 권한은 현재 참여 이력으로 판정한다. 재입장 시 새 참여 시작 이전 메시지와 읽음 상태를 노출하지 않는다. 재전송은 클라이언트 메시지 ID로 중복 저장을 방지한다. 채팅방 종료 즉시 일반 데이터의 접근을 차단하고, 확보한 신고 증거는 독립적으로 보존한다. [CHAT-002](https://app.notion.com/p/414f7bfe3aab8182b19ae499a3dc0216), [제약조건 및 데이터 보관 정책](https://app.notion.com/p/416f7bfe3aab8104a6a3efd6baced2db)

### Java 테스트

- 실행 명령의 특정 테스트 예시에서 테스트 클래스 이름을 바꿔 변경 영역을 확인한다.
- 상태 전이를 바꿀 때 권한 거절, 만료 경계, 중복·동시 요청을 검증한다. PostgreSQL 제약과 트랜잭션 동작에 의존하는 경로는 실제 PostgreSQL에서 검증한다.

## Spring 설정과 데이터베이스

이 범위는 Spring Boot 실행 설정과 향후 PostgreSQL 마이그레이션을 다룬다. 공통 설정은 `application.yaml`에 둔다. 로컬 실행과 통합 테스트는 각각 지정된 PostgreSQL 연결을 사용한다.

- MVP 논리 테이블 40개는 검수를 마쳤다. Spring Boot 시작 시 Flyway가 `src/main/resources/db/migration`의 마이그레이션을 적용한다. 첫 마이그레이션은 AUTH-002의 `users`와 `social_accounts`를 생성한다. `chat_notification_settings`는 후속 범위다. [스키마 설계](https://app.notion.com/p/4d5f7bfe3aab822cb59181283e740f40)
- 스키마 변경은 승인된 논리 설계를 먼저 갱신하고 마이그레이션으로 반영한다. 테이블명은 복수형, 컬럼명은 영어 snake_case를 사용한다. [테이블 상세 명세](https://app.notion.com/p/416f7bfe3aab811f85fcd50d790cdb53)

### PostgreSQL 제약과 삭제 정책

- 활성 그룹 소속과 채팅 참여 이력은 `left_at IS NULL`인 행의 부분 유일성으로, 진행 중 투표는 정렬된 그룹 쌍의 부분 유일성으로 보호한다. 매칭당 공식 채팅방 하나와 채팅 메시지 중복 방지 키도 스키마에 반영한다.
- 투표 자격과 소속 그룹, 메시지 발신 참여와 채팅방처럼 참조가 같은 범위에 속해야 하는 관계에는 복합 FK를 검토한다. 집계·상태·자격 조건은 서비스 트랜잭션과 함께 검증한다.
- 회원 탈퇴 시 공동 채팅의 작성자는 비식별화한다. 채팅방 종료 시 일반 메시지·사진 접근을 즉시 차단하고 외부 파일 삭제 실패를 재시도할 수 있게 처리한다. 신고 기록·증거는 원본과 독립적으로 보존하며 무조건적인 연쇄 삭제를 피한다. [제약조건 및 데이터 보관 정책](https://app.notion.com/p/416f7bfe3aab8104a6a3efd6baced2db)

### 설정·DB 테스트

- 설정 변경 후 실행 명령의 특정 테스트 예시로 `BackendApplicationTests`를 실행한다.
- DDL을 추가하거나 수정할 때 테스트용 PostgreSQL에 적용하고 FK, UNIQUE, CHECK, 부분 인덱스, 삭제 순서와 동시 상태 전이를 검증한다. [ERD 최종 검수](https://app.notion.com/p/416f7bfe3aab81e1b2cee73488614daa)

## Notion 참조

- [두밋 프로젝트](https://app.notion.com/p/40bf7bfe3aab80fab0eef8f68044a7b3)
- [전체 기능 명세서](https://app.notion.com/p/40bf7bfe3aab80eb900ae85eb762d2f6), [기술 설계서](https://app.notion.com/p/414f7bfe3aab8188bcbcf81a855142d3), [UI/UX 설계서](https://app.notion.com/p/414f7bfe3aab8148ad8cd138fe4f3f28)
- [백엔드 스키마 설계](https://app.notion.com/p/4d5f7bfe3aab822cb59181283e740f40), [API 명세서](https://app.notion.com/p/40bf7bfe3aab806bafc9daeecab2c3c8)
