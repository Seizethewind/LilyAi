"""LilyAi error types.

Purpose: one base class so callers can catch LilyAi failures together.
Depends on: nothing.
"""


class LilyError(Exception):
    """Base class for LilyAi errors."""


class ConfigError(LilyError):
    """Invalid or missing configuration."""


class SafetyError(LilyError):
    """Input, output or permission check failed."""


class ToolError(LilyError):
    """A tool failed to run."""
