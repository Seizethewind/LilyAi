package ai.lily.app.plugins

/** Builds small manifests for tests, and fakes for the two things a plugin tool talks to. */
object TestManifests {
    const val PKG = "com.example.app"
    const val PERMISSION = "external-app:com.example.app"
    const val RESULT = """{"pendingIntentExtra":"com.example.PI","bundleExtra":"result","outputKey":"stdout","errorOutputKey":"stderr","exitCodeKey":"exitCode","errorCodeKey":"err","errorCodeOk":-1,"errorMessageKey":"errmsg"}"""
    const val COMMAND_PARAM = """[{"name":"command","description":"what to run","required":true,"maxLength":50}]"""
    const val COMMAND_EXTRAS = """{"com.example.CMD":"{command}","com.example.BG":true}"""

    fun json(
        id: String = "test.plugin",
        version: String = "1.0.0",
        permissions: String = "\"$PERMISSION\"",
        toolName: String = "run",
        description: String = "Runs a thing",
        pkg: String = PKG,
        params: String = COMMAND_PARAM,
        extras: String = COMMAND_EXTRAS,
        result: String = RESULT,
        kind: String = "android-service",
    ): String = """
        {
          "schema": 1,
          "id": "$id",
          "name": "Test plugin",
          "version": "$version",
          "description": "A plugin for tests",
          "permissions": [$permissions],
          "tools": [
            {
              "name": "$toolName",
              "description": "$description",
              "params": $params,
              "action": {
                "kind": "$kind",
                "package": "$pkg",
                "class": "com.example.app.Service",
                "intentAction": "com.example.RUN",
                "extras": $extras,
                "result": $result,
                "timeoutMs": 5000
              }
            }
          ]
        }
    """.trimIndent()
}

class FakeBridge : ServiceBridge {
    var reply: ServiceReply = ServiceReply.Done("out", "", 0, -1, null)
    var failure: Throwable? = null
    val calls = mutableListOf<ServiceCall>()

    override suspend fun call(call: ServiceCall): ServiceReply {
        calls += call
        failure?.let { throw it }
        return reply
    }
}

class RecordingGate(var answer: Boolean) : ConfirmationGate {
    val requests = mutableListOf<ConfirmRequest>()

    override suspend fun confirm(request: ConfirmRequest): Boolean {
        requests += request
        return answer
    }
}

// A tool's action is now either an android-service or an http one. These let the older tests read the service fields.
val ToolAction.packageName: String get() = (this as ServiceAction).packageName
val ToolAction.extras: Map<String, ExtraValue> get() = (this as ServiceAction).extras
val ToolAction.result: ResultSpec? get() = (this as ServiceAction).result
val ToolAction.timeoutMs: Long get() = (this as ServiceAction).timeoutMs

class FakeHttpBridge : HttpBridge {
    var reply: HttpReply = HttpReply.Done(200, "{}", false)
    var failure: Throwable? = null
    val calls = mutableListOf<HttpCall>()

    override suspend fun call(call: HttpCall): HttpReply {
        calls += call
        failure?.let { throw it }
        return reply
    }
}

/** Builds small http plugin manifests for tests. The one tool is called "call"; its secret setting is "token". */
object HttpManifests {
    const val ID = "http.plugin"
    const val HOST_PERMISSION = "http:api.example.com"
    const val TOKEN = "tok_12345678"
    const val DEFAULT_PARAMS = """[{"name":"repo","description":"repo","required":true,"maxLength":50},{"name":"title","description":"title","required":false,"maxLength":200}]"""
    const val DEFAULT_SETTINGS = """[{"name":"token","label":"Token","secret":true}]"""

    fun json(
        permissions: String = "\"$HOST_PERMISSION\"",
        settings: String = DEFAULT_SETTINGS,
        params: String = DEFAULT_PARAMS,
        method: String = "POST",
        url: String = "https://api.example.com/repos/{repo}/pulls",
        headers: String = """{"Authorization":"Bearer {secret.token}"}""",
        body: String = """{"title":"{title}","draft":true}""",
        untrusted: Boolean = false,
    ): String = """
        {
          "schema": 1,
          "id": "$ID",
          "name": "Http test",
          "version": "1.0.0",
          "description": "An http plugin for tests",
          "permissions": [$permissions],
          "settings": $settings,
          "tools": [
            {
              "name": "call",
              "description": "Calls the api",
              "params": $params,
              "action": {
                "kind": "http",
                "method": "$method",
                "url": "$url",
                "headers": $headers,
                "body": $body,
                "timeoutMs": 5000,
                "untrusted": $untrusted
              }
            }
          ]
        }
    """.trimIndent()
}
