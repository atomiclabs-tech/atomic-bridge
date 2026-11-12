"""Relay command-line entry point and server lifecycle."""

import argparse
import asyncio
import logging
import json
from pathlib import Path
import sys

from aiohttp import web

from atomic_relay.config import Config, load_config
from atomic_relay import cli_remote
from atomic_relay.logsetup import configure_logging, configure_startup_logging
from atomic_relay.web.app import create_app

logger = logging.getLogger("atomic_relay")


async def serve(
    config: Config, *, stop: asyncio.Event | None = None,
    ready: asyncio.Future[tuple[str, int]] | None = None,
) -> None:
    """Serve until cancelled or stopped; optionally report the bound address to an embedder."""
    runner = web.AppRunner(create_app(config), access_log=None, shutdown_timeout=5)
    try:
        await asyncio.wait_for(runner.setup(), timeout=10)
        for host in config.hosts:
            site = web.TCPSite(runner, host, config.port)
            await asyncio.wait_for(site.start(), timeout=10)
        address = runner.addresses[0]
        logger.info("component=web operation=serve listening addresses=%s", runner.addresses)
        if ready is not None:
            ready.set_result((address[0], address[1]))
        await (stop if stop is not None else asyncio.Event()).wait()
    finally:
        await asyncio.wait_for(runner.cleanup(), timeout=10)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="atomic-relay")
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("serve", help="run the relay server")
    command.add_argument("--env-file", type=Path)
    cli_remote.add_commands(commands)
    args = parser.parse_args(argv)
    configure_startup_logging()
    try:
        if args.command in {"pair", "status"}:
            operation = cli_remote.pair if args.command == "pair" else cli_remote.status
            exit_code, result = operation(args)
            if result is not None:
                error_output = args.command == "pair" and args.register_code is None and exit_code != 0
                print(json.dumps(result), file=sys.stderr if error_output else sys.stdout)
            return exit_code
        config = load_config(args.env_file)
        configure_logging(config.log_file, config.log_level)
        asyncio.run(serve(config))
    except KeyboardInterrupt:
        logger.info("component=cli operation=serve stopped")
    except Exception:
        logger.exception("component=cli operation=serve startup or runtime failure")
        return 1
    return 0
