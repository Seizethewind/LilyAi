"""Client for external plugins / MCP-style servers."""


class PluginClient:
    def __init__(self, url: str) -> None:
        self.url = url

    def list_tools(self) -> list[str]:
        raise NotImplementedError

    def call(self, name: str, **kwargs) -> str:
        raise NotImplementedError
