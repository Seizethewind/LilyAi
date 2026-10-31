# Router: FreeLLMAPI

Source: https://github.com/Seizethewind/freellmapi (fork of tashfeenahmed/freellmapi)

- One OpenAI-compatible endpoint: `http://<host>:3001/v1`
- Auth: `Authorization: Bearer freellmapi-<unified-key>` (router dashboard, Keys page)
- Model field: `auto`, `auto:fast`, `auto:smart`, `auto:<profile>`, or a model id
- Response header `X-Routed-Via: <platform>/<model>`
- Also available: `/v1/embeddings`, `/v1/models`, `/v1/responses`, `/v1/images/generations`, `/v1/audio/*`, Anthropic `/v1/messages`, MCP at `/mcp`
- Free tiers: no frontier models, variable latency, no SLA; fails over on 429/5xx

## Used by LilyAI
- `gateway/RouterConfig`: URL, key, default model, web search model
- `gateway/RouterModelGateway`: `POST /v1/chat/completions`
- Keys are entered in Settings and stored encrypted

## Web search
`WebSearchTool` sends the query through the gateway using `RouterConfig.webSearchModel` (default `auto:search`).
Create a profile with that name in the router dashboard, or change the setting to whatever model/profile has search enabled.
TODO: confirm the router's exact web search request format and call it directly if it differs.

## Android note
The phone can only reach `127.0.0.1` if the router runs on the same phone (for example in Termux). Otherwise use a LAN or tunnel URL.
