# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Frozen argument contracts with Atomic descriptions."""

# forge-allow: no-test schema declarations only; parity is tested by test_tools_schemas.py.
SCHEMAS = {'android_setup': {'name': 'android_setup',
                   'description': 'Diagnose Atomic Bridge using loopback health, status and ping requests. '
                                  'Optionally register a pairing code without expiry; supplied session '
                                  'tokens are ignored. No settings are written.',
                   'parameters': {'type': 'object',
                                  'properties': {'bridge_session_token': {'type': 'string',
                                                                          'description': 'Accepted and '
                                                                                         'ignored. Atomic Bridge '
                                                                                         'reads credentials '
                                                                                         'from the '
                                                                                         'configured '
                                                                                         'sessions file.'},
                                                 'pairing_code': {'type': 'string',
                                                                  'description': 'Six-character pairing code '
                                                                                 'registered for Atomic Bridge '
                                                                                 'without expiry.'}},
                                  'required': []}},
 'android_macro': {'name': 'android_macro',
                   'description': 'Run an ordered workflow through the Atomic Bridge tools, stop at the first '
                                  'error, and retain screenshot summaries only.',
                   'parameters': {'type': 'object',
                                  'properties': {'steps': {'type': 'array',
                                                           'description': 'Ordered list of step dicts. Each '
                                                                          "step must have a 'tool' key (one "
                                                                          'of the android_* tool names) and '
                                                                          "may have an 'args' key (kwargs "
                                                                          'for that tool). Example: '
                                                                          '[{"tool": "android_tap", "args": '
                                                                          '{"x": 100, "y": 200}}, {"tool": '
                                                                          '"android_type", "args": {"text": '
                                                                          '"hello"}}]',
                                                           'items': {'type': 'object',
                                                                     'properties': {'tool': {'type': 'string',
                                                                                             'description': 'android_* '
                                                                                                            'tool '
                                                                                                            'name'},
                                                                                    'args': {'type': 'object',
                                                                                             'description': 'kwargs '
                                                                                                            'for '
                                                                                                            'the '
                                                                                                            'tool '
                                                                                                            '(optional)'}},
                                                                     'required': ['tool']}},
                                                 'name': {'type': 'string',
                                                          'description': 'Human-readable Atomic Bridge workflow '
                                                                         'label returned in the result.',
                                                          'default': 'unnamed'},
                                                 'pace_ms': {'type': 'integer',
                                                             'description': 'Milliseconds to sleep between '
                                                                            'steps. 0 disables pacing.',
                                                             'default': 500},
                                                 'device': {'type': 'string',
                                                            'description': 'Optional Android device selector '
                                                                           'for multi-device relays. Accepts '
                                                                           'a device_id or alias such as '
                                                                           'phone, pixel, fold, boox, note, '
                                                                           'notemax, or tablet. Omit to use '
                                                                           "the relay's active/default "
                                                                           'device.'}},
                                  'required': ['steps']}}}
