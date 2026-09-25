"""Small, redacted error contract shared by sponsor adapters."""
from __future__ import annotations


class IntegrationError(RuntimeError):
    def __init__(self, code: str, message: str, retryable: bool = False):
        super().__init__(message)
        self.code = code
        self.retryable = retryable


class AgentUnavailable(IntegrationError):
    def __init__(self, message: str, code: str = "agent_unavailable"):
        super().__init__(code, message, False)
