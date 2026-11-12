# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Frozen argument contracts with Atomic descriptions."""

# forge-allow: no-test schema declarations only; parity is tested by test_tools_schemas.py.
SCHEMAS = {'android_ping': {'name': 'android_ping',
                  'description': 'Ask the phone app to report whether it is responding.',
                  'parameters': {'type': 'object',
                                 'properties': {'device': {'type': 'string',
                                                           'description': 'Optional Android device selector '
                                                                          'for multi-device relays. Accepts '
                                                                          'a device_id or alias such as '
                                                                          'phone, pixel, fold, boox, note, '
                                                                          'notemax, or tablet. Omit to use '
                                                                          "the relay's active/default "
                                                                          'device.'}},
                                 'required': []}},
 'android_read_screen': {'name': 'android_read_screen',
                         'description': 'Read the accessibility tree on Atomic Bridge, with optional node bounds.',
                         'parameters': {'type': 'object',
                                        'properties': {'include_bounds': {'type': 'boolean',
                                                                          'description': 'Include pixel '
                                                                                         'coordinates for '
                                                                                         'each node. Default '
                                                                                         'false.',
                                                                          'default': False},
                                                       'device': {'type': 'string',
                                                                  'description': 'Optional Android device '
                                                                                 'selector for multi-device '
                                                                                 'relays. Accepts a '
                                                                                 'device_id or alias such as '
                                                                                 'phone, pixel, fold, boox, '
                                                                                 'note, notemax, or tablet. '
                                                                                 "Omit to use the relay's "
                                                                                 'active/default device.'}},
                                        'required': []}},
 'android_find_nodes': {'name': 'android_find_nodes',
                        'description': 'Find matching accessibility nodes on Atomic Bridge using combined optional '
                                       'filters.',
                        'parameters': {'type': 'object',
                                       'properties': {'text': {'type': 'string',
                                                               'description': 'Case-insensitive substring to '
                                                                              'match against node text or '
                                                                              'contentDescription.'},
                                                      'class_name': {'type': 'string',
                                                                     'description': 'Exact Android class '
                                                                                    'name to match, e.g. '
                                                                                    "'android.widget.Button'."},
                                                      'clickable': {'type': 'boolean',
                                                                    'description': 'If set, only return '
                                                                                   'nodes with matching '
                                                                                   'isClickable flag.'},
                                                      'limit': {'type': 'integer',
                                                                'description': 'Max number of matches to '
                                                                               'return (default 20, '
                                                                               'hard-capped at 512).',
                                                                'default': 20},
                                                      'device': {'type': 'string',
                                                                 'description': 'Optional Android device '
                                                                                'selector for multi-device '
                                                                                'relays. Accepts a device_id '
                                                                                'or alias such as phone, '
                                                                                'pixel, fold, boox, note, '
                                                                                'notemax, or tablet. Omit to '
                                                                                "use the relay's "
                                                                                'active/default device.'}},
                                       'required': []}},
 'android_describe_node': {'name': 'android_describe_node',
                           'description': 'Read the full property bag for one accessibility node on Atomic Bridge.',
                           'parameters': {'type': 'object',
                                          'properties': {'node_id': {'type': 'string',
                                                                     'description': 'Stable node ID from '
                                                                                    'android_read_screen '
                                                                                    "(e.g. 'w0:42')"},
                                                         'device': {'type': 'string',
                                                                    'description': 'Optional Android device '
                                                                                   'selector for '
                                                                                   'multi-device relays. '
                                                                                   'Accepts a device_id or '
                                                                                   'alias such as phone, '
                                                                                   'pixel, fold, boox, note, '
                                                                                   'notemax, or tablet. Omit '
                                                                                   "to use the relay's "
                                                                                   'active/default device.'}},
                                          'required': ['node_id']}},
 'android_screen_hash': {'name': 'android_screen_hash',
                         'description': 'Get a SHA-256 fingerprint of the current accessibility tree on '
                                        'Atomic Bridge.',
                         'parameters': {'type': 'object',
                                        'properties': {'device': {'type': 'string',
                                                                  'description': 'Optional Android device '
                                                                                 'selector for multi-device '
                                                                                 'relays. Accepts a '
                                                                                 'device_id or alias such as '
                                                                                 'phone, pixel, fold, boox, '
                                                                                 'note, notemax, or tablet. '
                                                                                 "Omit to use the relay's "
                                                                                 'active/default device.'}},
                                        'required': []}},
 'android_diff_screen': {'name': 'android_diff_screen',
                         'description': 'Compare the current Atomic Bridge screen with an earlier '
                                        'accessibility-tree hash.',
                         'parameters': {'type': 'object',
                                        'properties': {'previous_hash': {'type': 'string',
                                                                         'description': 'Prior hash from '
                                                                                        'android_screen_hash.'},
                                                       'device': {'type': 'string',
                                                                  'description': 'Optional Android device '
                                                                                 'selector for multi-device '
                                                                                 'relays. Accepts a '
                                                                                 'device_id or alias such as '
                                                                                 'phone, pixel, fold, boox, '
                                                                                 'note, notemax, or tablet. '
                                                                                 "Omit to use the relay's "
                                                                                 'active/default device.'}},
                                        'required': ['previous_hash']}},
 'android_current_app': {'name': 'android_current_app',
                         'description': 'Get the foreground package and activity on Atomic Bridge.',
                         'parameters': {'type': 'object',
                                        'properties': {'device': {'type': 'string',
                                                                  'description': 'Optional Android device '
                                                                                 'selector for multi-device '
                                                                                 'relays. Accepts a '
                                                                                 'device_id or alias such as '
                                                                                 'phone, pixel, fold, boox, '
                                                                                 'note, notemax, or tablet. '
                                                                                 "Omit to use the relay's "
                                                                                 'active/default device.'}},
                                        'required': []}},
 'android_screenshot': {'name': 'android_screenshot',
                        'description': 'Capture the Atomic Bridge screen and attach a bounded PNG or JPEG image '
                                       'with a text summary.',
                        'parameters': {'type': 'object',
                                       'properties': {'sensitive': {'type': 'boolean',
                                                                    'description': 'Mark the screenshot '
                                                                                   'media token private. '
                                                                                   'Defaults to false.'},
                                                      'device': {'type': 'string',
                                                                 'description': 'Optional Android device '
                                                                                'selector for multi-device '
                                                                                'relays. Accepts a device_id '
                                                                                'or alias such as phone, '
                                                                                'pixel, fold, boox, note, '
                                                                                'notemax, or tablet. Omit to '
                                                                                "use the relay's "
                                                                                'active/default device.'}},
                                       'required': []}}}
