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
