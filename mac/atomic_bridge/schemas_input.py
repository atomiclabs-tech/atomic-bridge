# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Frozen argument contracts with Atomic descriptions."""

# forge-allow: no-test schema declarations only; parity is tested by test_tools_schemas.py.
SCHEMAS = {'android_tap': {'name': 'android_tap',
                 'description': 'Tap a node on Atomic Bridge by node_id, or tap screen coordinates x and y.',
                 'parameters': {'type': 'object',
                                'properties': {'x': {'type': 'integer',
                                                     'description': 'X coordinate in pixels'},
                                               'y': {'type': 'integer',
                                                     'description': 'Y coordinate in pixels'},
                                               'node_id': {'type': 'string',
                                                           'description': 'Accessibility node ID from '
                                                                          'android_read_screen'},
                                               'device': {'type': 'string',
                                                          'description': 'Optional Android device selector '
                                                                         'for multi-device relays. Accepts a '
                                                                         'device_id or alias such as phone, '
                                                                         'pixel, fold, boox, note, notemax, '
                                                                         "or tablet. Omit to use the relay's "
                                                                         'active/default device.'}},
                                'required': []}},
 'android_tap_text': {'name': 'android_tap_text',
                      'description': 'Tap the first visible text match on Atomic Bridge, using exact or substring '
                                     'matching.',
                      'parameters': {'type': 'object',
                                     'properties': {'text': {'type': 'string',
                                                             'description': 'Text to find and tap'},
                                                    'exact': {'type': 'boolean',
                                                              'description': 'Exact match (true) or contains '
                                                                             'match (false, default)',
                                                              'default': False},
                                                    'device': {'type': 'string',
                                                               'description': 'Optional Android device '
                                                                              'selector for multi-device '
                                                                              'relays. Accepts a device_id '
                                                                              'or alias such as phone, '
                                                                              'pixel, fold, boox, note, '
                                                                              'notemax, or tablet. Omit to '
                                                                              "use the relay's "
                                                                              'active/default device.'}},
                                     'required': ['text']}},
 'android_long_press': {'name': 'android_long_press',
                        'description': 'Long press a node or screen coordinates on Atomic Bridge. Duration must be '
                                       '100 to 3000 ms.',
                        'parameters': {'type': 'object',
                                       'properties': {'x': {'type': 'integer',
                                                            'description': 'X coordinate in pixels'},
                                                      'y': {'type': 'integer',
                                                            'description': 'Y coordinate in pixels'},
                                                      'node_id': {'type': 'string',
                                                                  'description': 'Accessibility node ID from '
                                                                                 'android_read_screen'},
                                                      'duration': {'type': 'integer',
                                                                   'description': 'Hold duration in '
                                                                                  'milliseconds (100..3000)',
                                                                   'default': 500},
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
 'android_type': {'name': 'android_type',
                  'description': 'Type into the focused field on Atomic Bridge, optionally clearing the existing '
                                 'text.',
                  'parameters': {'type': 'object',
                                 'properties': {'text': {'type': 'string', 'description': 'Text to type'},
                                                'clear_first': {'type': 'boolean',
                                                                'description': 'Clear existing content '
                                                                               'before typing',
                                                                'default': False},
                                                'device': {'type': 'string',
                                                           'description': 'Optional Android device selector '
                                                                          'for multi-device relays. Accepts '
                                                                          'a device_id or alias such as '
                                                                          'phone, pixel, fold, boox, note, '
                                                                          'notemax, or tablet. Omit to use '
                                                                          "the relay's active/default "
                                                                          'device.'}},
                                 'required': ['text']}},
 'android_swipe': {'name': 'android_swipe',
                   'description': 'Swipe up, down, left or right on Atomic Bridge with the selected distance.',
                   'parameters': {'type': 'object',
                                  'properties': {'direction': {'type': 'string',
                                                               'enum': ['up', 'down', 'left', 'right']},
                                                 'distance': {'type': 'string',
                                                              'enum': ['short', 'medium', 'long'],
                                                              'default': 'medium'},
                                                 'device': {'type': 'string',
                                                            'description': 'Optional Android device selector '
                                                                           'for multi-device relays. Accepts '
                                                                           'a device_id or alias such as '
                                                                           'phone, pixel, fold, boox, note, '
                                                                           'notemax, or tablet. Omit to use '
                                                                           "the relay's active/default "
                                                                           'device.'}},
                                  'required': ['direction']}},
 'android_drag': {'name': 'android_drag',
                  'description': 'Drag between screen coordinates on Atomic Bridge. Duration is clamped to 100 to '
                                 '3000 ms. Optionally hold at the start point before moving.',
                  'parameters': {'type': 'object',
                                 'properties': {'start_x': {'type': 'integer',
                                                            'description': 'Start X coordinate in pixels'},
                                                'start_y': {'type': 'integer',
                                                            'description': 'Start Y coordinate in pixels'},
                                                'end_x': {'type': 'integer',
                                                          'description': 'End X coordinate in pixels'},
                                                'end_y': {'type': 'integer',
                                                          'description': 'End Y coordinate in pixels'},
                                                'duration': {'type': 'integer',
                                                             'description': 'Gesture duration in '
                                                                            'milliseconds (100–3000, default '
                                                                            '500)',
                                                             'default': 500},
                                                'hold_ms': {'type': 'integer',
                                                            'default': 0,
                                                            'description': 'Hold at the start point before moving, '
                                                                           '0 to 3000 ms (default 0). Use 500 or '
                                                                           'more to pick up a launcher icon.'},
                                                'device': {'type': 'string',
                                                           'description': 'Optional Android device selector '
                                                                          'for multi-device relays. Accepts '
                                                                          'a device_id or alias such as '
                                                                          'phone, pixel, fold, boox, note, '
                                                                          'notemax, or tablet. Omit to use '
                                                                          "the relay's active/default "
                                                                          'device.'}},
                                 'required': ['start_x', 'start_y', 'end_x', 'end_y']}},
 'android_scroll': {'name': 'android_scroll',
                    'description': 'Scroll the Atomic Bridge screen or a selected scrollable node.',
                    'parameters': {'type': 'object',
                                   'properties': {'direction': {'type': 'string',
                                                                'enum': ['up', 'down', 'left', 'right']},
                                                  'node_id': {'type': 'string',
                                                              'description': 'Node ID of scrollable '
                                                                             'container (optional, defaults '
                                                                             'to screen scroll)'},
                                                  'device': {'type': 'string',
                                                             'description': 'Optional Android device '
                                                                            'selector for multi-device '
                                                                            'relays. Accepts a device_id or '
                                                                            'alias such as phone, pixel, '
                                                                            'fold, boox, note, notemax, or '
                                                                            "tablet. Omit to use the relay's "
                                                                            'active/default device.'}},
                                   'required': ['direction']}},
 'android_press_key': {'name': 'android_press_key',
                       'description': 'Press a supported hardware or software key on Atomic Bridge.',
                       'parameters': {'type': 'object',
                                      'properties': {'key': {'type': 'string',
                                                             'enum': ['back',
                                                                      'home',
                                                                      'recents',
                                                                      'power',
                                                                      'volume_up',
                                                                      'volume_down',
                                                                      'enter',
                                                                      'delete',
                                                                      'tab',
                                                                      'escape',
                                                                      'search',
                                                                      'notifications']},
                                                     'device': {'type': 'string',
                                                                'description': 'Optional Android device '
                                                                               'selector for multi-device '
                                                                               'relays. Accepts a device_id '
                                                                               'or alias such as phone, '
                                                                               'pixel, fold, boox, note, '
                                                                               'notemax, or tablet. Omit to '
                                                                               "use the relay's "
                                                                               'active/default device.'}},
                                      'required': ['key']}},
 'android_wait': {'name': 'android_wait',
                  'description': 'Wait for a matching accessibility node to appear on Atomic Bridge.',
                  'parameters': {'type': 'object',
                                 'properties': {'text': {'type': 'string',
                                                         'description': 'Wait for element with this text'},
                                                'class_name': {'type': 'string',
                                                               'description': 'Wait for element of this '
                                                                              'class'},
                                                'timeout_ms': {'type': 'integer',
                                                               'description': 'Max wait time in milliseconds',
                                                               'default': 5000},
                                                'device': {'type': 'string',
                                                           'description': 'Optional Android device selector '
                                                                          'for multi-device relays. Accepts '
                                                                          'a device_id or alias such as '
                                                                          'phone, pixel, fold, boox, note, '
                                                                          'notemax, or tablet. Omit to use '
                                                                          "the relay's active/default "
                                                                          'device.'}},
                                 'required': []}}}
