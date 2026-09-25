"""Real sponsor probes. Run explicitly; RawTree writes one labeled synthetic event."""
import argparse
import asyncio
import json
import os
from backend.integrations.common import IntegrationError


async def check(name, args):
    if name == "liquid":
        from backend.integrations.liquid import LiquidClient
        adapter = LiquidClient()
        operation = adapter.echo_nonce_smoke
    elif name == "rawtree":
        if not os.getenv("RAWTREE_API_KEY"):
            return {"service": name, "status": "unavailable", "code": "missing_credentials"}
        from backend.integrations.rawtree import RawTreeClient
        adapter = RawTreeClient.from_env()
        operation = adapter.smoke_test
    else:
        if not os.getenv("NIMBLE_API_KEY"):
            return {"service": name, "status": "unavailable", "code": "missing_credentials"}
        from backend.integrations.nimble import NimbleClient
        adapter = NimbleClient()

        async def operation():
            search = await adapter.search(args.category, args.city)
            if not search.get("sources"):
                raise IntegrationError("no_sources", "Live search returned no public sources.")
            evidence = await adapter.extract(search["sources"][0])
            return {"source_url": evidence["url"], "retrieved_at": evidence["retrieved_at"], "evidence_ref": evidence["evidence_ref"], "verification_status": evidence["verification_status"], "facts": evidence.get("facts"), "request_id": search.get("request_id")}
    try:
        result = await operation()
        return {"service": name, "status": "passed", "real_network_call": True, "result": result}
    except IntegrationError as error:
        return {"service": name, "status": "unavailable" if error.code in ("missing_credentials", "agent_unavailable", "model_unavailable") else "failed", "code": error.code, "retryable": error.retryable, "message": str(error)}
    except Exception as error:
        # Never dump request bodies or client exceptions that may contain credentials.
        return {"service": name, "status": "failed", "code": "unexpected_error", "error_type": type(error).__name__}
    finally:
        close = getattr(adapter, "aclose", None)
        if close:
            await close()


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", choices=["liquid", "rawtree", "nimble", "all"])
    parser.add_argument("--env-file", help="Optional local dotenv file; secrets are never printed.")
    parser.add_argument("--category", default="caregiver support groups")
    parser.add_argument("--city", default="Oakland")
    args = parser.parse_args()
    if args.env_file:
        from dotenv import load_dotenv
        load_dotenv(args.env_file, override=False)
    names = ["liquid", "rawtree", "nimble"] if args.service == "all" else [args.service]
    results = [await check(name, args) for name in names]
    print(json.dumps(results, indent=2))
    return 0 if all(r["status"] == "passed" for r in results) else 2


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
