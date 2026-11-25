"""Run tools, checking permissions first."""
from safety.permissions import Permissions
from tools.tool_registry import ToolRegistry


class ToolExecutor:
    def __init__(self, registry: ToolRegistry, permissions: Permissions) -> None:
        self.registry = registry
        self.permissions = permissions

    def execute(self, name: str, **kwargs) -> str:
        if not self.permissions.is_allowed(name):
            raise PermissionError(f"Tool not allowed: {name}")
        return self.registry.get(name)(**kwargs)
