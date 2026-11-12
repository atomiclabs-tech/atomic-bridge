# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Frozen argument contracts with Atomic descriptions."""

# forge-allow: no-test schema declarations only; parity is tested by test_tools_schemas.py.
SCHEMAS = {'android_open_app': {'name': 'android_open_app',
                      'description': 'Launch an installed package on Atomic Bridge.',
                      'parameters': {'type': 'object',
                                     'properties': {'package': {'type': 'string',
                                                                'description': 'App package name e.g. '
                                                                               'com.ubercab'},
                                                    'device': {'type': 'string',
                                                               'description': 'Optional Android device '
                                                                              'selector for multi-device '
                                                                              'relays. Accepts a device_id '
                                                                              'or alias such as phone, '
                                                                              'pixel, fold, boox, note, '
                                                                              'notemax, or tablet. Omit to '
                                                                              "use the relay's "
                                                                              'active/default device.'}},
                                     'required': ['package']}},
 'android_get_apps': {'name': 'android_get_apps',
                      'description': 'List installed packages and display labels on Atomic Bridge.',
                      'parameters': {'type': 'object',
                                     'properties': {'device': {'type': 'string',
                                                               'description': 'Optional Android device '
                                                                              'selector for multi-device '
                                                                              'relays. Accepts a device_id '
                                                                              'or alias such as phone, '
                                                                              'pixel, fold, boox, note, '
                                                                              'notemax, or tablet. Omit to '
                                                                              "use the relay's "
                                                                              'active/default device.'}},
                                     'required': []}},
 'android_send_intent': {'name': 'android_send_intent',
                         'description': 'Send an Android activity intent from Atomic Bridge with the supplied '
                                        'fields.',
                         'parameters': {'type': 'object',
                                        'properties': {'action': {'type': 'string',
                                                                  'description': 'Android action string, '
                                                                                 'e.g. '
                                                                                 "'android.intent.action.VIEW'"},
                                                       'data': {'type': 'string',
                                                                'description': 'Optional data URI for the '
                                                                               'Intent'},
                                                       'package': {'type': 'string',
                                                                   'description': 'Optional target package — '
                                                                                  'forces the Intent to a '
                                                                                  'specific app'},
                                                       'component': {'type': 'string',
                                                                     'description': 'Optional '
                                                                                    'fully-qualified '
                                                                                    'component '
                                                                                    "'pkg/classname'"},
                                                       'extras': {'type': 'object',
                                                                  'description': 'Optional '
                                                                                 'string-keyed/string-valued '
                                                                                 'Intent extras',
                                                                  'additionalProperties': {'type': 'string'}},
                                                       'category': {'type': 'string',
                                                                    'description': 'Optional Intent category '
                                                                                   '(e.g. '
                                                                                   "'android.intent.category.LAUNCHER')"},
                                                       'device': {'type': 'string',
                                                                  'description': 'Optional Android device '
                                                                                 'selector for multi-device '
                                                                                 'relays. Accepts a '
                                                                                 'device_id or alias such as '
                                                                                 'phone, pixel, fold, boox, '
                                                                                 'note, notemax, or tablet. '
                                                                                 "Omit to use the relay's "
                                                                                 'active/default device.'}},
                                        'required': ['action']}},
 'android_broadcast': {'name': 'android_broadcast',
                       'description': 'Send an Android broadcast from Atomic Bridge with the supplied fields.',
                       'parameters': {'type': 'object',
                                      'properties': {'action': {'type': 'string',
                                                                'description': 'Android action string '
                                                                               '(required)'},
                                                     'data': {'type': 'string',
                                                              'description': 'Optional data URI'},
                                                     'package': {'type': 'string',
                                                                 'description': 'Optional target package — '
                                                                                'scopes the broadcast to a '
                                                                                'single app'},
                                                     'extras': {'type': 'object',
                                                                'description': 'Optional '
                                                                               'string-keyed/string-valued '
                                                                               'Intent extras',
                                                                'additionalProperties': {'type': 'string'}},
                                                     'device': {'type': 'string',
                                                                'description': 'Optional Android device '
                                                                               'selector for multi-device '
                                                                               'relays. Accepts a device_id '
                                                                               'or alias such as phone, '
                                                                               'pixel, fold, boox, note, '
                                                                               'notemax, or tablet. Omit to '
                                                                               "use the relay's "
                                                                               'active/default device.'}},
                                      'required': ['action']}},
 'android_events': {'name': 'android_events',
                    'description': 'Read recent accessibility events captured by the phone app.',
                    'parameters': {'type': 'object',
                                   'properties': {'limit': {'type': 'integer',
                                                            'description': 'Max entries to return (1-500, '
                                                                           'default 50)',
                                                            'default': 50},
                                                  'since': {'type': 'integer',
                                                            'description': 'Only return entries with '
                                                                           'timestamp > this epoch '
                                                                           'millisecond value. Use the '
                                                                           'timestamp of the last entry you '
                                                                           'saw to poll incrementally. '
                                                                           'Default 0 (return everything in '
                                                                           'the buffer).',
                                                            'default': 0},
                                                  'device': {'type': 'string',
                                                             'description': 'Optional Android device '
                                                                            'selector for multi-device '
                                                                            'relays. Accepts a device_id or '
                                                                            'alias such as phone, pixel, '
                                                                            'fold, boox, note, notemax, or '
                                                                            "tablet. Omit to use the relay's "
                                                                            'active/default device.'}},
                                   'required': []}},
 'android_event_stream': {'name': 'android_event_stream',
                          'description': 'Start or stop accessibility-event capture on Atomic Bridge. Stopping '
                                         'clears its event buffer.',
                          'parameters': {'type': 'object',
                                         'properties': {'enabled': {'type': 'boolean',
                                                                    'description': 'true = start capture, '
                                                                                   'false = stop + clear '
                                                                                   'buffer'},
                                                        'device': {'type': 'string',
                                                                   'description': 'Optional Android device '
                                                                                  'selector for multi-device '
                                                                                  'relays. Accepts a '
                                                                                  'device_id or alias such '
                                                                                  'as phone, pixel, fold, '
                                                                                  'boox, note, notemax, or '
                                                                                  'tablet. Omit to use the '
                                                                                  "relay's active/default "
                                                                                  'device.'}},
                                         'required': ['enabled']}},
 'android_clipboard_read': {'name': 'android_clipboard_read',
                            'description': 'Read the current plain-text clipboard on Atomic Bridge.',
                            'parameters': {'type': 'object',
                                           'properties': {'device': {'type': 'string',
                                                                     'description': 'Optional Android device '
                                                                                    'selector for '
                                                                                    'multi-device relays. '
                                                                                    'Accepts a device_id or '
                                                                                    'alias such as phone, '
                                                                                    'pixel, fold, boox, '
                                                                                    'note, notemax, or '
                                                                                    'tablet. Omit to use the '
                                                                                    "relay's active/default "
                                                                                    'device.'}},
                                           'required': []}},
 'android_clipboard_write': {'name': 'android_clipboard_write',
                             'description': 'Write plain text to the clipboard on Atomic Bridge.',
                             'parameters': {'type': 'object',
                                            'properties': {'text': {'type': 'string',
                                                                    'description': 'Text to copy to the '
                                                                                   'clipboard'},
                                                           'device': {'type': 'string',
                                                                      'description': 'Optional Android '
                                                                                     'device selector for '
                                                                                     'multi-device relays. '
                                                                                     'Accepts a device_id or '
                                                                                     'alias such as phone, '
                                                                                     'pixel, fold, boox, '
                                                                                     'note, notemax, or '
                                                                                     'tablet. Omit to use '
                                                                                     "the relay's "
                                                                                     'active/default '
                                                                                     'device.'}},
                                            'required': ['text']}},
 'android_media': {'name': 'android_media',
                   'description': 'Control system media playback on Atomic Bridge: play, pause, toggle, next or '
                                  'previous.',
                   'parameters': {'type': 'object',
                                  'properties': {'action': {'type': 'string',
                                                            'enum': ['play',
                                                                     'pause',
                                                                     'toggle',
                                                                     'next',
                                                                     'previous'],
                                                            'description': 'Playback action to dispatch'},
                                                 'device': {'type': 'string',
                                                            'description': 'Optional Android device selector '
                                                                           'for multi-device relays. Accepts '
                                                                           'a device_id or alias such as '
                                                                           'phone, pixel, fold, boox, note, '
                                                                           'notemax, or tablet. Omit to use '
                                                                           "the relay's active/default "
                                                                           'device.'}},
                                  'required': ['action']}}}
