package ai.lily.app.gateway

/**
 * Every route of Seizethewind/freellmapi, read from its source at 2236367 on 2026-11-05: 225 endpoints in 32 groups.
 * Nothing is left out because the app does not call it today; the list is the reference for future features.
 * Left out on purpose: the single-page dashboard fallback that serves the web app for unknown addresses.
 * /v1/models is served twice (Anthropic format when an anthropic-version header is sent, OpenAI format otherwise) and listed once.
 * Proxy, responses and Ollama routes are also served under /v1/t/:token; those copies are in the "URL token" group.
 * Line format: see [EndpointTable]. Auth: O no key, K API key, U main API key only, S dashboard login, L this machine only, T token in the address.
 */
object FreeLlmApiEndpoints {
    val all: List<RouterEndpoint> = EndpointTable.parse(TABLE)
}

private val TABLE = """
# Analytics
GET /api/analytics/by-client S
GET /api/analytics/by-key S
GET /api/analytics/by-model S
GET /api/analytics/by-platform S
GET /api/analytics/error-distribution S
GET /api/analytics/errors S
GET /api/analytics/requests S
GET /api/analytics/requests/:id S
GET /api/analytics/search S
GET /api/analytics/summary S
GET /api/analytics/timeline S
# Anthropic (v1)
POST /v1/messages K
POST /v1/messages/count_tokens K
# Backups
GET /api/backups S
POST /api/backups S
DELETE /api/backups/:id S
GET /api/backups/:id/download S
POST /api/backups/:id/restore S
GET /api/backups/schedule S
PUT /api/backups/schedule S
GET /api/backups/tables S
# Cache
DELETE /api/cache S
PUT /api/cache/config S
GET /api/cache/stats S
# Client profiles
GET /api/client-profiles S
POST /api/client-profiles S
DELETE /api/client-profiles/:id S
PATCH /api/client-profiles/:id S
POST /api/client-profiles/:id/rotate S
# Compression
POST /api/compression/preview S
GET /api/compression/stats S
# Conversations
GET /api/conversations S
POST /api/conversations S
DELETE /api/conversations/:id S
GET /api/conversations/:id S
PUT /api/conversations/:id S
# Dashboard auth
POST /api/auth/change-email S
POST /api/auth/change-password S
POST /api/auth/desktop-session O
POST /api/auth/forgot-password O
POST /api/auth/login O
POST /api/auth/logout O
GET /api/auth/me S
POST /api/auth/reset-password O
POST /api/auth/setup O
GET /api/auth/status O
# Decisions (admin)
GET /api/decisions/models S
PATCH /api/decisions/models/:id S
POST /api/decisions/try S
# Docs
GET /v1/docs O
GET /v1/openapi.json O
# Embeddings (admin)
GET /api/embeddings S
PUT /api/embeddings S
POST /api/embeddings/custom S
DELETE /api/embeddings/custom/:id S
GET /api/embeddings/usage S
# Fallback and routing
GET /api/fallback S
PUT /api/fallback S
DELETE /api/fallback/penalty-inspector S
GET /api/fallback/penalty-inspector S
GET /api/fallback/quota-forecast S
GET /api/fallback/rate-limit-usage S
GET /api/fallback/routing S
PUT /api/fallback/routing S
POST /api/fallback/sort/:preset S
GET /api/fallback/token-usage S
# Free tier
GET /api/free-tier S
# Gemini (v1beta)
GET /v1beta/models K
GET /v1beta/models/*model K
POST /v1beta/models/*model:countTokens K
POST /v1beta/models/*model:generateContent K
POST /v1beta/models/*model:streamGenerateContent K
# Inference (v1)
POST /v1/audio/speech K
POST /v1/audio/transcriptions K
POST /v1/chat/completions K
POST /v1/completions K
POST /v1/decisions K
GET /v1/decisions/models K
POST /v1/embeddings K
POST /v1/images/generations K
GET /v1/models K
GET /v1/models/*rest K
GET /v1/models/:id K
POST /v1/rerank K
POST /v1/search K
GET /v1/search/engines K
POST /v1/systemone K
GET /v1/usage U
POST /v1/videos/generations K
# Key health
GET /api/health S
POST /api/health/check-all S
POST /api/health/check/:keyId S
POST /api/health/probe-models S
POST /api/health/quota/:keyId S
# Keys
GET /api/keys S
POST /api/keys S
DELETE /api/keys/:id S
PATCH /api/keys/:id S
DELETE /api/keys/:id/cooldowns S
POST /api/keys/:id/reveal S
POST /api/keys/custom S
POST /api/keys/custom/discover-models S
POST /api/keys/custom/probe S
POST /api/keys/discovered-models S
GET /api/keys/export S
POST /api/keys/import S
POST /api/keys/import-selected S
PATCH /api/keys/platform/:platform S
POST /api/keys/preview S
GET /api/keys/providers S
# Logs
GET /api/logs S
POST /api/logs/clear S
# MCP server
DELETE /mcp U
GET /mcp U
POST /mcp U
# Media (admin)
GET /api/media S
PUT /api/media/:id S
POST /api/media/custom S
DELETE /api/media/custom/:id S
GET /api/media/usage S
# Models (admin)
GET /api/models S
POST /api/models S
DELETE /api/models/:id S
PATCH /api/models/:id S
POST /api/models/:id/test S
DELETE /api/models/custom/:id S
# Moderation (v1)
POST /v1/moderations K
# Ollama
POST /api/chat K
POST /api/embed K
POST /api/embeddings K
POST /api/generate K
POST /api/show K
GET /api/tags K
GET /api/version K
# Premium
GET /api/premium S
DELETE /api/premium/key S
POST /api/premium/key S
POST /api/premium/portal S
POST /api/premium/sync S
# Provider status (v1)
GET /v1/providers U
GET /v1/quota-forecast U
# Responses (v1)
POST /v1/responses K
# Routing profiles
GET /api/profiles S
POST /api/profiles S
DELETE /api/profiles/:id S
PUT /api/profiles/:id S
GET /api/profiles/:id/models S
PUT /api/profiles/:id/reorder S
POST /api/profiles/:id/reset S
POST /api/profiles/:id/sort/:preset S
GET /api/profiles/active S
POST /api/profiles/active S
# Search (admin)
GET /api/search/engines S
PATCH /api/search/engines/:id S
# Service status
GET /api/ping O
GET /livez O
GET /readyz O
# Settings
GET /api/settings/agent-compatibility S
PUT /api/settings/agent-compatibility S
GET /api/settings/anthropic-map S
PUT /api/settings/anthropic-map S
GET /api/settings/api-key S
POST /api/settings/api-key/regenerate S
GET /api/settings/compression S
PUT /api/settings/compression S
GET /api/settings/enable-mcp S
PUT /api/settings/enable-mcp S
GET /api/settings/fusion S
PUT /api/settings/fusion S
GET /api/settings/gemini-map S
PUT /api/settings/gemini-map S
GET /api/settings/guardrails S
PUT /api/settings/guardrails S
GET /api/settings/headroom S
PUT /api/settings/headroom S
GET /api/settings/min-context-window S
PUT /api/settings/min-context-window S
GET /api/settings/min-reliability-floor S
PUT /api/settings/min-reliability-floor S
GET /api/settings/output-limit S
PUT /api/settings/output-limit S
GET /api/settings/provider-moment-attribution S
PUT /api/settings/provider-moment-attribution S
GET /api/settings/proxy S
PUT /api/settings/proxy S
POST /api/settings/proxy/test S
GET /api/settings/task-weight-share S
PUT /api/settings/task-weight-share S
GET /api/settings/unify S
PUT /api/settings/unify S
GET /api/settings/update-check S
PUT /api/settings/update-check S
GET /api/settings/url-tokens S
POST /api/settings/url-tokens S
DELETE /api/settings/url-tokens/:id S
GET /api/settings/version S
# URL token (v1/t/:token)
POST /v1/t/:token/api/chat T
POST /v1/t/:token/api/embed T
POST /v1/t/:token/api/embeddings T
POST /v1/t/:token/api/generate T
POST /v1/t/:token/api/show T
GET /v1/t/:token/api/tags T
GET /v1/t/:token/api/version T
POST /v1/t/:token/v1/audio/speech T
POST /v1/t/:token/v1/audio/transcriptions T
POST /v1/t/:token/v1/chat/completions T
POST /v1/t/:token/v1/completions T
POST /v1/t/:token/v1/decisions T
GET /v1/t/:token/v1/decisions/models T
POST /v1/t/:token/v1/embeddings T
POST /v1/t/:token/v1/images/generations T
GET /v1/t/:token/v1/models T
GET /v1/t/:token/v1/models/*rest T
GET /v1/t/:token/v1/models/:id T
POST /v1/t/:token/v1/rerank T
POST /v1/t/:token/v1/responses T
POST /v1/t/:token/v1/search T
GET /v1/t/:token/v1/search/engines T
POST /v1/t/:token/v1/systemone T
GET /v1/t/:token/v1/usage T
POST /v1/t/:token/v1/videos/generations T
# Updates
POST /api/update/apply S
GET /api/update/check S
GET /api/update/release S
GET /api/update/status S
"""
