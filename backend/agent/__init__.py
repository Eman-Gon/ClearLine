"""Factories consumed by Session 2's durable worker integration seam."""


def create_agent_runner():
    from backend.integrations.liquid import LiquidClient
    from backend.integrations.rawtree import RawTreeClient
    from backend.integrations.nimble import NimbleClient
    from .runner import LiquidAgentRunner
    return LiquidAgentRunner(LiquidClient(), RawTreeClient.from_env(), NimbleClient())


def create_exporter():
    from backend.integrations.rawtree import RawTreeClient
    return RawTreeClient.from_env()
