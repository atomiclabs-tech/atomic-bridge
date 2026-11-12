# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Frozen argument contracts with Atomic descriptions."""

# forge-allow: no-test schema declarations only; parity is tested by test_tools_schemas.py.
SCHEMAS = {'android_location': {'name': 'android_location',
                      'description': 'Get the last known phone location from Atomic Bridge; missing permission is '
                                     'reported as an error.',
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
 'android_search_contacts': {'name': 'android_search_contacts',
                             'description': 'Search contacts on Atomic Bridge by name and return matching phone '
                                            'numbers.',
                             'parameters': {'type': 'object',
                                            'properties': {'query': {'type': 'string',
                                                                     'description': 'Contact name or '
                                                                                    'fragment to search for'},
                                                           'limit': {'type': 'integer',
                                                                     'description': 'Maximum number of '
                                                                                    'results (default 20, '
                                                                                    'max 100)',
                                                                     'default': 20},
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
                                            'required': ['query']}},
 'android_call': {'name': 'android_call',
                  'description': 'Place a phone call from Atomic Bridge to the supplied number.',
                  'parameters': {'type': 'object',
                                 'properties': {'number': {'type': 'string',
                                                           'description': 'Phone number to dial (any '
                                                                          'user-readable format)'},
                                                'device': {'type': 'string',
                                                           'description': 'Optional Android device selector '
                                                                          'for multi-device relays. Accepts '
                                                                          'a device_id or alias such as '
                                                                          'phone, pixel, fold, boox, note, '
                                                                          'notemax, or tablet. Omit to use '
                                                                          "the relay's active/default "
                                                                          'device.'}},
                                 'required': ['number']}},
 'android_send_sms': {'name': 'android_send_sms',
                      'description': 'Send an SMS from Atomic Bridge and report the phone app result.',
                      'parameters': {'type': 'object',
                                     'properties': {'to': {'type': 'string',
                                                           'description': 'Recipient phone number (E.164 or '
                                                                          'local format)'},
                                                    'body': {'type': 'string',
                                                             'description': 'SMS body text'},
                                                    'device': {'type': 'string',
                                                               'description': 'Optional Android device '
                                                                              'selector for multi-device '
                                                                              'relays. Accepts a device_id '
                                                                              'or alias such as phone, '
                                                                              'pixel, fold, boox, note, '
                                                                              'notemax, or tablet. Omit to '
                                                                              "use the relay's "
                                                                              'active/default device.'}},
                                     'required': ['to', 'body']}},
 'android_share_media': {'name': 'android_share_media',
                         'description': 'Share text or attachments through Atomic Bridge. Host files are registered '
                                        'before the phone app fetches them.',
                         'parameters': {'type': 'object',
                                        'properties': {'path': {'type': 'string',
                                                                'description': 'Host-local file path to '
                                                                               'share'},
                                                       'paths': {'type': 'array',
                                                                 'description': 'Multiple host-local file '
                                                                                'paths to share',
                                                                 'items': {'type': 'string'}},
                                                       'media': {'type': 'string',
                                                                 'description': 'Relay MEDIA marker or raw '
                                                                                'media token to share'},
                                                       'media_token': {'type': 'string',
                                                                       'description': 'Raw relay media '
                                                                                      'token'},
                                                       'media_tokens': {'type': 'array',
                                                                        'description': 'Multiple raw relay '
                                                                                       'media tokens',
                                                                        'items': {'type': 'string'}},
                                                       'attachments': {'type': 'array',
                                                                       'description': 'Attachment objects '
                                                                                      'with one of path, '
                                                                                      'media, token, or '
                                                                                      'media_token plus '
                                                                                      'optional content_type '
                                                                                      'and file_name',
                                                                       'items': {'type': 'object',
                                                                                 'properties': {'path': {'type': 'string'},
                                                                                                'media': {'type': 'string'},
                                                                                                'token': {'type': 'string'},
                                                                                                'media_token': {'type': 'string'},
                                                                                                'content_type': {'type': 'string'},
                                                                                                'file_name': {'type': 'string'}}}},
                                                       'content_type': {'type': 'string',
                                                                        'description': 'Optional MIME type '
                                                                                       'hint for a single '
                                                                                       'path/media value'},
                                                       'file_name': {'type': 'string',
                                                                     'description': 'Optional filename hint '
                                                                                    'for a single path/media '
                                                                                    'value'},
                                                       'text': {'type': 'string',
                                                                'description': 'Optional text to share with '
                                                                               'attachments'},
                                                       'title': {'type': 'string',
                                                                 'description': 'Optional Android chooser '
                                                                                'title'},
                                                       'package': {'type': 'string',
                                                                   'description': 'Optional target package '
                                                                                  'to launch directly '
                                                                                  'instead of the chooser'},
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
 'android_send_mms': {'name': 'android_send_mms',
                      'description': 'Prepare an MMS compose handoff on Atomic Bridge with text or attachments. '
                                     'This does not confirm carrier delivery.',
                      'parameters': {'type': 'object',
                                     'properties': {'to': {'type': 'string',
                                                           'description': 'Recipient phone number'},
                                                    'body': {'type': 'string',
                                                             'description': 'Optional message body'},
                                                    'path': {'type': 'string',
                                                             'description': 'Host-local file path to attach'},
                                                    'paths': {'type': 'array',
                                                              'description': 'Multiple host-local file paths '
                                                                             'to attach',
                                                              'items': {'type': 'string'}},
                                                    'media': {'type': 'string',
                                                              'description': 'Relay MEDIA marker or raw '
                                                                             'media token'},
                                                    'media_token': {'type': 'string',
                                                                    'description': 'Raw relay media token'},
                                                    'media_tokens': {'type': 'array',
                                                                     'description': 'Multiple raw relay '
                                                                                    'media tokens',
                                                                     'items': {'type': 'string'}},
                                                    'attachments': {'type': 'array',
                                                                    'description': 'Attachment objects with '
                                                                                   'one of path, media, '
                                                                                   'token, or media_token '
                                                                                   'plus optional '
                                                                                   'content_type and '
                                                                                   'file_name',
                                                                    'items': {'type': 'object',
                                                                              'properties': {'path': {'type': 'string'},
                                                                                             'media': {'type': 'string'},
                                                                                             'token': {'type': 'string'},
                                                                                             'media_token': {'type': 'string'},
                                                                                             'content_type': {'type': 'string'},
                                                                                             'file_name': {'type': 'string'}}}},
                                                    'content_type': {'type': 'string',
                                                                     'description': 'Optional MIME type hint '
                                                                                    'for a single path/media '
                                                                                    'value'},
                                                    'file_name': {'type': 'string',
                                                                  'description': 'Optional filename hint for '
                                                                                 'a single path/media value'},
                                                    'package': {'type': 'string',
                                                                'description': 'Optional SMS/MMS app package '
                                                                               'to launch directly'},
                                                    'device': {'type': 'string',
                                                               'description': 'Optional Android device '
                                                                              'selector for multi-device '
                                                                              'relays. Accepts a device_id '
                                                                              'or alias such as phone, '
                                                                              'pixel, fold, boox, note, '
                                                                              'notemax, or tablet. Omit to '
                                                                              "use the relay's "
                                                                              'active/default device.'}},
                                     'required': ['to']}},
 'android_return_to_hermes': {'name': 'android_return_to_hermes',
                              'description': 'Request return to the phone app. Atomic Bridge currently reports '
                                             'unsupported_command for this command.',
                              'parameters': {'type': 'object',
                                             'properties': {'device': {'type': 'string',
                                                                       'description': 'Optional Android '
                                                                                      'device selector for '
                                                                                      'multi-device relays. '
                                                                                      'Accepts a device_id '
                                                                                      'or alias such as '
                                                                                      'phone, pixel, fold, '
                                                                                      'boox, note, notemax, '
                                                                                      'or tablet. Omit to '
                                                                                      "use the relay's "
                                                                                      'active/default '
                                                                                      'device.'}},
                                             'required': []}}}
