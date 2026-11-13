"""Build the relay HTTP application and its health route."""

import asyncio
import logging
import time
from collections.abc import Callable

from aiohttp import web

from atomic_relay.config import Config
from atomic_relay.web import routes_bridge, routes_media, routes_notifications, routes_pairing, routes_sessions, ws
from atomic_relay.web import routes_unlock
from atomic_relay.web.errors import log_failure, request_errors
from atomic_relay.web.guard import ADMIN_SECRET, admin_middleware, has_admin
from atomic_relay.web.runtime import RUNTIME, RelayRuntime

logger = logging.getLogger("atomic_relay.media")


def create_app(config: Config, *, clock: Callable[[], float] = time.monotonic) -> web.Application:
    """Create an isolated application; health uses monotonic process uptime."""
    started = clock()
    runtime = RelayRuntime(config, clock=clock)
    protected = (routes_pairing.PAIRING_PATHS | routes_bridge.BRIDGE_PATHS | routes_media.MEDIA_PATHS
                 | routes_notifications.NOTIFICATION_PATHS | routes_sessions.SESSION_PATHS
                 | set(routes_bridge.GET_PATHS) | set(routes_bridge.POST_PATHS) | routes_unlock.UNLOCK_PATHS)
    app = web.Application(middlewares=[request_errors, admin_middleware(protected)])
    app[RUNTIME] = runtime
    app[ADMIN_SECRET] = config.admin_secret

    async def health(request: web.Request) -> web.Response:
        body = {
            "status": "ok", "version": config.server_version,
            "uptime_seconds": clock() - started,
        }
        if has_admin(request):
            body.update(clients=len(runtime.clients), sessions=runtime.sessions.active_count(),
                        phone_connected=runtime.registry.is_phone_connected())
        return web.json_response(body)

    app.router.add_get("/health", health)
    routes_pairing.add_routes(app)
    routes_sessions.add_routes(app)
    routes_bridge.add_routes(app)
    routes_unlock.add_routes(app)
    routes_media.add_routes(app)
    routes_notifications.add_routes(app)
    app.router.add_get("/ws", ws.handle_ws)
    app.router.add_get("/", ws.handle_ws)

    async def shutdown(application):
        runtime.notification_waiters.close()
        runtime.dispatch.close()
        for socket in tuple(runtime.clients):
            await asyncio.wait_for(socket.close(code=1001, message=b"Server shutting down"), timeout=5)
        application[RUNTIME].sessions.flush(force=True)

    app.on_shutdown.append(shutdown)

    async def media_lifecycle(application):
        task = asyncio.create_task(runtime.media.maintain())
        try:
            yield
        finally:
            task.cancel()
            try:
                await asyncio.wait_for(task, timeout=5)
            except asyncio.CancelledError:
                logger.debug("component=media operation=maintenance stopped")
            try:
                application[RUNTIME].media.close()
            except OSError as error:
                log_failure(logger, error, "component=media operation=shutdown cleanup")

    app.cleanup_ctx.append(media_lifecycle)
    return app
