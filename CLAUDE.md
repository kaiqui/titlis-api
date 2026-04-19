# CLAUDE.md — titlis-api

> Após toda alteração: `./gradlew test` deve passar. Sem testes falhando, sem PR.
> Para rodar apenas unit tests rápidos: `./gradlew test --tests "*.unit.*"`

---

## 1. Stack

| Categoria | Tecnologia | Versão |
|---|---|---|
| Linguagem | Kotlin | 2.1.0 |
| Framework HTTP | Ktor (Netty) | 3.1.0 |
| JVM | Eclipse Temurin | 21 |
| Build | Gradle (Kotlin DSL) | 8.10+ |
| ORM | JetBrains Exposed (DSL) | 0.60.0 |
| Pool | HikariCP | 6.2.1 |
| Banco | PostgreSQL | 15 |
| JWT (local) | Auth0 Java JWT | — |
| Serialização | Kotlinx Serialization JSON | 1.8.0 |
| Datas | Kotlinx Datetime | 0.6.2 |
| Testes | Kotest + MockK + H2 in-memory | — |
| Logs | Logback + Logstash encoder (JSON) | — |

---

## 2. Estrutura de Pacotes

```
src/main/kotlin/io/titlis/api/
├── Main.kt                    # embeddedServer(Netty) + module()
├── auth/
│   ├── AppAuthentication.kt   # Plugins Ktor de auth (appAuth, oktaJwtAuth)
│   ├── AppPrincipal.kt        # Principal: userId, tenantId, email, role, provider
│   ├── AuthMode.kt            # Enum: DISABLED | OKTA | MIXED
│   ├── AuthModels.kt          # Data classes de request/response de auth
│   ├── AuthSupport.kt         # Extensões para Principal em rotas
│   ├── Authorization.kt       # requireAdminPrincipal(), requirePrincipal()
│   ├── LocalTokenService.kt   # Gera e valida JWT HS256
│   ├── OidcDiscoveryVerifier.kt # Valida .well-known/openid-configuration
│   ├── OktaTokenVerifier.kt   # Valida JWT RS256 do Okta
│   ├── PasswordHasher.kt      # Hash + verificação de senha
│   └── RequestAuthenticator.kt # Orquestra todos os mecanismos de auth
├── config/
│   └── AppConfig.kt           # Carrega HOCON + env vars (propertyOrEnv())
├── database/
│   ├── DatabaseFactory.kt     # HikariCP + SchemaUtils.createMissingTablesAndColumns()
│   └── tables/
│       ├── OltpTables.kt      # Tabelas principais (titlis_oltp.*)
│       ├── AuditTables.kt     # Histórico (titlis_audit.*)
│       ├── TsTables.kt        # Séries temporais (titlis_ts.*)
│       ├── PgEnums.kt         # Tipo de coluna enum PostgreSQL
│       └── JsonColumns.kt     # Coluna JSONB helper
├── domain/
│   └── Events.kt              # Modelos de eventos UDP
├── repository/
│   ├── AuthRepository.kt      # CRUD de users, auth integrations, invites
│   ├── ScorecardRepository.kt # Upsert + query de scorecards
│   ├── RemediationRepository.kt # Estado de remediação
│   ├── SloRepository.kt       # SLO configs
│   ├── MetricsRepository.kt   # Insert time-series
│   └── TenantSupport.kt       # Helpers: resolveTenantIdByWorkloadId(), chooseTenantId()
├── routes/
│   ├── AuthRoutes.kt          # /v1/auth/*
│   ├── HealthRoutes.kt        # /health, /ready
│   ├── ScorecardRoutes.kt     # /v1/dashboard, /v1/workloads/:id/scorecard
│   ├── RemediationRoutes.kt   # /v1/workloads/:id/remediation
│   ├── SloRoutes.kt           # /v1/slos, /v1/namespaces/:ns/slos/:name
│   ├── SettingsAuthRoutes.kt  # /v1/settings/auth/providers
│   ├── RagRoutes.kt           # /v1/internal/rag/* (Phase 2 — knowledge base pgvector)
│   ├── InternalAiRoutes.kt    # /v1/internal/ai/* (Phase 3 — contexto vivo para titlis-ai)
│   └── JsonResponses.kt       # Helpers de serialização JSON
└── udp/
    ├── UdpServer.kt           # Socket UDP porta 8125, worker pool de coroutines
    └── EventRouter.kt         # Dispatch por tipo de evento
```

---

## 3. Startup

**`Main.kt`** — `fun main()`:
1. `embeddedServer(Netty, port = 8080)` com `module()`
2. `AppConfig.from(environment.config)` — lê `application.conf` + env vars
3. `DatabaseFactory.init(config)` — HikariCP + criação de tabelas
4. Instancia repositórios (singletons)
5. Instancia `LocalTokenService`, `OktaTokenVerifier`, `RequestAuthenticator`
6. `UdpServer.start(port = 8125)` em coroutine separada
7. Registra plugins Ktor: `ContentNegotiation`, `Authentication`, `StatusPages`
8. Registra todas as rotas

---

## 4. Banco de Dados

### Schemas

| Schema | Tabelas principais | Padrão de escrita |
|---|---|---|
| `titlis_oltp` | tenants, workloads, app_scorecards, validation_rules, app_remediations, slo_configs, platform_users, tenant_auth_integrations, platform_user_invites, **tenant_ai_config**, **ai_knowledge_chunks**, **slo_config_pending_changes** | UPSERT / UPDATE in-place |
| `titlis_audit` | app_scorecard_history, remediation_history, slo_compliance_history, notification_log, **ai_interactions**, **ai_feedback** | INSERT append-only |
| `titlis_ts` | resource_metrics, scorecard_scores | INSERT append-only |

### Novas tabelas (AI + SLO)

**`titlis_oltp.tenant_ai_config`** — configuração de provider AI por tenant:
```sql
tenant_id              BIGINT PK  → tenants
provider               TEXT       -- openai | anthropic | google | ...
model                  TEXT       -- gpt-4o | claude-3-5-sonnet | ...
api_key_enc            BYTEA      -- criptografado
github_token_enc       BYTEA      -- token GitHub para abrir PRs
github_base_branch     TEXT       -- padrão: main
monthly_token_budget   INT        -- NULL = ilimitado
tokens_used_month      INT
is_active              BOOLEAN
created_at, updated_at
```

**`titlis_oltp.ai_knowledge_chunks`** — base RAG com pgvector:
```sql
id           UUID         PRIMARY KEY
tenant_id    BIGINT       -- NULL = conhecimento global
source_type  TEXT         -- rule_doc | k8s_best_practice | past_remediation
source_id    TEXT         -- rule_id ou pr_url
chunk_text   TEXT
embedding    VECTOR(1536)
metadata     JSONB
created_at   TIMESTAMP
```

**`titlis_oltp.slo_config_pending_changes`** — fila de mudanças de SLO propostas pelo titlis-ai:
```sql
id              UUID          PRIMARY KEY
tenant_id       BIGINT        → tenants
slo_config_name TEXT          -- nome do SLOConfig CRD
namespace       TEXT
field           TEXT          -- "target" | "warning" | "timeframe"
old_value       TEXT
new_value       TEXT
requested_by    TEXT          -- "titlis-ai" | "user:{user_id}"
status          TEXT          -- pending | applied | failed | cancelled
created_at      TIMESTAMPTZ
applied_at      TIMESTAMPTZ
error           TEXT          -- preenchido se status=failed
```

### Convenções Exposed

```kotlin
// Sempre use dbQuery {} para operações de banco (Dispatchers.IO)
suspend fun findByWorkload(workloadId: Long): AppScorecard? = dbQuery {
    AppScorecards
        .select { AppScorecards.workloadId eq workloadId }
        .singleOrNull()
        ?.let { mapRow(it) }
}

// UPSERT com upsert {} do Exposed
AppScorecards.upsert {
    it[workloadId] = ...
    it[overallScore] = ...
}

// JSONB com JsonColumns helper
it[labels] = Json.encodeToString(mapOf("app" to "my-app"))
```

### Isolation
- `TRANSACTION_REPEATABLE_READ` (configurado no HikariCP)
- Todos os queries filtram por `tenantId` — **nunca esqueça este filtro**

### Schema File
O schema autoritativo é `titlis-operator/db/schema.sql`. Não há migrations automáticas.
Novas tabelas são criadas por `createMissingTablesAndColumns()`. Alterações em colunas
existentes precisam de DDL manual.

---

## 5. Endpoints REST

**Base URL:** `http://localhost:8080`

### Públicos (sem auth)
```
GET  /health                         → { status: "ok" }
GET  /ready                          → 200 se banco disponível
GET  /v1/auth/bootstrap/status       → { bootstrapRequired: bool }
POST /v1/auth/bootstrap/setup        → cria primeiro tenant + admin
POST /v1/auth/local/login            → { accessToken, expiresAt, user }
```

### Autenticados
```
GET  /v1/auth/me                     → { user: AppPrincipal }

# Scorecards
GET  /v1/dashboard?cluster=x         → [ WorkloadSummary ]
GET  /v1/workloads/:id/scorecard     → WorkloadDetail (score + findings + pillars)
GET  /v1/workloads/:id/remediation   → { status, pr_url, pr_number, triggered_at }

# SLOs
GET  /v1/slos?namespace=x&cluster=y  → [ SloConfig ]
GET  /v1/namespaces/:ns/slos/:name   → SloConfig

# Settings (Admin only — 403 se não for admin)
GET  /v1/settings/auth/providers             → [ TenantAuthIntegration ]
POST /v1/settings/auth/providers             → cria/atualiza provider OIDC
POST /v1/settings/auth/providers/:id/verify  → valida OIDC discovery
POST /v1/settings/auth/providers/:id/activate   → ativa como primário
POST /v1/settings/auth/providers/:id/deactivate → desativa

GET  /v1/settings/api-keys                   → [ ApiKeyListItem ]
POST /v1/settings/api-keys                   → { id, prefix, rawToken, ... } (201)
DELETE /v1/settings/api-keys/:id             → 204 / 404
GET  /v1/settings/api-keys/connection-status → { connected, lastEventAt, activeKeyCount }
  # connected=true quando ao menos uma chave ativa foi usada pelo operator (lastUsedAt != null)
```

### Internos (titlis-ai → titlis-api, via X-Internal-Secret)

**RAG (Phase 2):**
```
POST /v1/internal/rag/chunks                    → armazena chunk + embedding
POST /v1/internal/rag/search                    → busca por similaridade coseno (top-K)
```

**Contexto vivo para o agente (Phase 3):**
```
GET  /v1/internal/ai/scorecards/{uid}           → scorecard por k8s_uid
GET  /v1/internal/ai/scorecards/by-name         → scorecard por name+namespace
GET  /v1/internal/ai/dashboard                  → lista de workloads com scores
GET  /v1/internal/ai/scorecards/similar-resolved → workloads que resolveram uma regra
GET  /v1/internal/ai/slos                       → SLOs do tenant
GET  /v1/internal/ai/remediations/{uid}/history → histórico de remediações do workload
POST /v1/internal/ai/slo-configs/{id}/propose-change → propõe mudança de threshold
```

**Operator (polling de mudanças de SLO):**
```
GET  /v1/operator/pending-slo-changes           → mudanças status=pending do tenant (API key auth)
POST /v1/operator/pending-slo-changes/{id}/applied → marca como aplicada
POST /v1/operator/pending-slo-changes/{id}/failed  → registra falha
```

Todos os endpoints `/v1/internal/*` são autenticados por `X-Internal-Secret` (não por JWT).
Os endpoints `/v1/operator/*` são autenticados por API key do operator.

### Erros HTTP
- `400` — payload inválido (body malformado, campo faltando)
- `401` — token ausente, expirado ou inválido
- `403` — autenticado mas sem permissão (ex: não é admin)
- `404` — recurso não encontrado
- `409` — conflito (bootstrap já configurado, tenant slug em uso)
- `500` — erro interno (log estruturado + stack trace)

---

## 6. Autenticação

### Mecanismos suportados

**1. Local (HS256)**
```
POST /v1/auth/local/login { tenantSlug, email, password }
→ PasswordHasher.verify(password, hash)
→ LocalTokenService.issue(userId, tenantId, role)
→ JWT HS256 assinado com TITLIS_AUTH_ACCESS_TOKEN_SECRET
Claims: sub, tenantId, role, authProvider="local", iat, exp
```

**2. Okta/OIDC (RS256)**
```
UI redireciona para Okta → Okta retorna JWT RS256
API valida via OktaTokenVerifier:
  - Baixa JWKS do issuer
  - Valida assinatura RS256
  - Extrai claims: titlis_tenant_id, titlis_roles, email
```

**3. Dev Bypass** (apenas `TITLIS_APP_ENV=local`)
```
Header: X-Dev-Auth: true
Opcional: X-Dev-Tenant-Id, X-Dev-User, X-Dev-Roles
→ Cria AppPrincipal sintético sem verificar banco
```

### RequestAuthenticator — fluxo
```
requestPath em /health, /ready, /v1/auth/* → pula auth
├── Header X-Dev-Auth + app_env=local → dev bypass
├── Header Authorization: Bearer <token>
│   ├── Tenta LocalTokenService.verify()
│   └── Se falhar, tenta OktaTokenVerifier.verify()
└── Sem token → 401
```

### Authorization
```kotlin
// Em qualquer rota protegida:
val principal = call.requirePrincipal()          // lança 401 se ausente
val admin = call.requireAdminPrincipal()         // lança 403 se não for ADMIN
```

---

## 7. UDP Event Ingestion

**`UdpServer`:** escuta em `0.0.0.0:8125` (UDP), buffer de 65507 bytes,
pool de 4 workers (coroutines em `Dispatchers.IO`).

**`EventRouter`:** parseia o envelope e despacha:
```kotlin
when (event.t) {
    "scorecard_evaluated" → scorecardRepository.upsert(...)
    "remediation_started" → remediationRepository.start(...)
    "remediation_updated" → remediationRepository.update(...)
    "slo_reconciled"      → sloRepository.upsert(...)
    "notification_sent"   → auditRepository.logNotification(...)
    "resource_metrics"    → metricsRepository.insert(...)
    else                  → log.warn("unknown event type")
}
```

**Resolução de tenant_id:**
1. Usa `envelope.tenant_id` se presente
2. Busca via `TenantSupport.resolveTenantIdByWorkloadId(workloadId)`
3. Fallback: `chooseTenantId()` — único tenant ativo (single-tenant mode)
4. Se nada resolve: descarta o evento com log de warning

---

## 8. Variáveis de Ambiente

```bash
# Banco
DATABASE_URL=jdbc:postgresql://localhost:5432/titlis
DATABASE_USER=titlis
DATABASE_PASSWORD=titlis
DB_POOL_MAX=10
DB_POOL_CONNECTION_TIMEOUT=30000
DB_POOL_IDLE_TIMEOUT=600000

# HTTP
PORT=8080

# UDP
TITLIS_UDP_PORT=8125
TITLIS_UDP_BUFFER_SIZE=65507
TITLIS_UDP_WORKERS=4
TITLIS_UDP_QUEUE_SIZE=10000

# Ambiente e auth
TITLIS_APP_ENV=local           # local | dev | staging | production
TITLIS_AUTH_MODE=mixed         # disabled | okta | mixed
TITLIS_AUTH_ISSUER=titlis-local
TITLIS_AUTH_AUDIENCE=titlis-ui
TITLIS_AUTH_ACCESS_TOKEN_SECRET=titlis-dev-secret-change-me
TITLIS_AUTH_ACCESS_TOKEN_TTL_MINUTES=720

# Dev bypass
TITLIS_DEV_BYPASS_ENABLED=true
TITLIS_DEV_TENANT_ID=1
TITLIS_DEV_USER_EMAIL=dev@titlis.local
TITLIS_DEV_ROLES=titlis.admin

# Okta (necessário se auth_mode=okta ou mixed)
TITLIS_OKTA_ISSUER=https://trial-xxxx.okta.com/oauth2/ausXXX
TITLIS_OKTA_AUDIENCE=api://titlis
TITLIS_OKTA_CLIENT_ID=xxx
```

---

## 9. Comandos Gradle

```bash
./gradlew run                    # Roda localmente
./gradlew test                   # Todos os testes
./gradlew test --tests "*.Unit*" # Apenas unit tests
./gradlew clean build            # Build completo com testes
./gradlew installDist            # Gera distribuição (usado no Docker)
```

**Docker:**
```bash
docker build -t kailima/titlis-api:latest .
# Multi-stage: gradle:8.10.2-jdk21-alpine (build) → eclipse-temurin:21-jre-alpine (runtime)
# Expõe: 8080/tcp, 8125/udp
```

---

## 10. Convenções de Código

### Padrão de rota
```kotlin
// Sempre extrai principal antes de qualquer I/O
get("/v1/workloads/{id}/scorecard") {
    val principal = call.requirePrincipal()
    val workloadId = call.parameters["id"]?.toLongOrNull()
        ?: return@get call.respond(HttpStatusCode.BadRequest)
    val result = scorecardRepository.findByWorkload(workloadId, principal.tenantId)
        ?: return@get call.respond(HttpStatusCode.NotFound)
    call.respondJson(result)
}
```

### Padrão de repositório
```kotlin
// Toda função que acessa banco é suspend e usa dbQuery {}
// Filtra SEMPRE por tenantId
// Usa UPSERT para eventos idempotentes
// Insere em audit tables separadas (não modifica a principal)
```

### Tratamento de erro
```kotlin
// StatusPages plugin mapeia exceções → HTTP status codes
// Exceções de negócio customizadas: InvalidCredentialsException, BootstrapAlreadyConfiguredException
// Nunca exponha stack traces na resposta HTTP (apenas no log)
```

### Serialização
```kotlin
// Usa @Serializable do kotlinx.serialization
// Campos nullable com default null
// Datas como Instant (kotlinx-datetime)
// Enums serializados como strings lowercase
```

---

## 11. Testes

```bash
./gradlew test                   # Roda todos
```

**Estrutura:**
```
src/test/kotlin/io/titlis/api/
├── auth/          # Testa LocalTokenService, PasswordHasher, RequestAuthenticator
├── routes/        # Testa endpoints com TestApplication (Ktor test engine)
├── repository/    # Testa repositories com H2 in-memory
└── udp/           # Testa EventRouter com eventos sintéticos
```

**Padrões:**
- Use `testApplication {}` do Ktor para testar rotas completas
- Use H2 in-memory para testes de repositório (configurado em `application-test.conf`)
- MockK para mockar dependências externas
- Todos os testes de rota devem testar o cenário de `requirePrincipal()` retornando 401

---

## 12. Integração com titlis-ai

### InternalAiRoutes.kt

7 endpoints sob `/v1/internal/ai/`, todos autenticados por `X-Internal-Secret`.
Registrado em `Main.kt` como:
```kotlin
internalAiRoutes(scorecardRepo, remediationRepo, sloRepo, config.aiService.internalSecret)
```

Principais métodos adicionados nos repositories:
- `ScorecardRepository.getByName(name, namespace, tenantId)` — busca por nome+namespace
- `ScorecardRepository.getSimilarResolved(ruleId, tenantId, limit)` — workloads que passaram na regra
- `RemediationRepository.getHistory(k8sUid, tenantId)` — até 20 registros por workload

### RagRoutes.kt

Endpoints de pgvector para a base RAG do titlis-ai:
- `POST /v1/internal/rag/chunks` — persiste chunk com embedding VECTOR(1536)
- `POST /v1/internal/rag/search` — busca por similaridade coseno com filtro de `source_type` e `tenant_id`

---

## 13. O Que Não Fazer

- **Nunca** use `transaction {}` no lugar de `dbQuery {}` — o `dbQuery` gerencia o dispatcher correto
- **Nunca** omita o filtro `tenantId` em queries — viola isolamento multi-tenant
- **Nunca** exponha `password_hash` em qualquer resposta JSON
- **Nunca** aceite `X-Dev-Auth` header sem checar `TITLIS_APP_ENV=local`
- **Nunca** faça `SchemaUtils.drop()` — sem migration, sem `DROP`
- **Nunca** adicione colunas NOT NULL sem DEFAULT em tabelas existentes (quebra inicialização)
- **Nunca** use `upsert` irrestrito em colunas âncora de isolamento (ex.: `Clusters.tenantId`) —
  use select-first + validação de ownership; veja `ScorecardRepository.ensureCluster()`
- **Nunca** adicione guard "só pode existir um tenant" em `setupBootstrap()` — o sistema é
  multi-tenant por design; a proteção de duplicata é por `tenantSlug`, não por contagem de usuários
