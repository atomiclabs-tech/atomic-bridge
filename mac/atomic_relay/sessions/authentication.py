# Ported from plugin/relay/server.py at upstream commit da782a8.
"""Authenticate in upstream order without depending on a transport."""

import logging

logger = logging.getLogger("atomic_relay")


class Authentication:
    def __init__(self, sessions, pairing, rate_limiter):
        self.sessions, self.pairing, self.rate_limiter = sessions, pairing, rate_limiter

    def authenticate(self, payload: dict, address: str, *, transport_hint="unknown"):
        token, refresh, code = (payload.get(key, "") for key in ("session_token", "refresh_token", "pairing_code"))
        if any(not isinstance(value, str) for value in (token, refresh, code)):
            raise ValueError("authentication credentials must be strings")
        device_id = payload.get("device_id", "unknown")
        name = payload.get("device_name") or payload.get("device_hostname") or "Unknown device"
        if not isinstance(device_id, str) or not isinstance(name, str):
            raise ValueError("authentication device identity must be strings")
        metadata = {key: payload[key] for key in ("client_surface", "device_form_factor", "device_model", "device_platform")
                    if key in payload}
        session = self.sessions.get_session(token) if token else None
        if session is not None:
            name_update = {"device_name": name} if "device_name" in payload or payload.get("device_hostname") else {}
            self.sessions.update_session_device_metadata(session, **name_update, **metadata)
            if not refresh and device_id and not self.sessions.has_trusted_device(session.device_id):
                self.sessions.issue_refresh_token_for_session(session)
        if session is None and refresh:
            session = self.sessions.refresh_session(refresh, device_name=name, device_id=device_id,
                                                    transport_hint=transport_hint, **metadata)
        if session is None and code:
            policy = self.pairing.consume_code(code)
            if policy is not None:
                session = self.sessions.create_session(
                    name, device_id, ttl_seconds=policy.ttl_seconds, grants=policy.grants,
                    transport_hint=policy.transport_hint or transport_hint, issue_refresh_token=True, **metadata,
                )
        if session is not None:
            self.rate_limiter.record_success(address)
            return session
        if token or not (refresh or code):
            self.rate_limiter.record_session_failure(address)
        if refresh:
            self.rate_limiter.record_session_failure(address)
        if code:
            self.rate_limiter.record_pairing_failure(address)
        logger.info("Auth failed")
        return None
