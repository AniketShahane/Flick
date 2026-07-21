# Flick control v2 — normative fixtures

All examples are synthetic test values. They are exact compact JSON text, encoded as UTF-8 in one unfragmented WebSocket text message. The displayed bytes are the reference serialization for fixture tests; schema validation is object-order independent but must preserve the same unique keys, values, types, and duplicate-key rejection. Every frame forbids extra fields.

Status: mirrored by the green 29-test sender and 38-test receiver JVM runs; both debug APKs build. Real-device protocol exchange has not yet been exercised.

## Test symbols

| Symbol | Value |
|---|---|
| `tvId` | `ABEiM0RVZneImaq7zN3u_w` |
| `keyId` | `AQIDBAUGBwgJCgsMDQ4PEA` |
| `clientNonce` | `ERITFBUWFxgZGhscHR4fIA` |
| `serverNonce` | `ISIjJCUmJygpKissLS4vMA` |
| `castId` | `MDEyMzQ1Njc4OWFiY2RlZg` |
| `mediaToken` | `ZGVtb19tZWRpYV90b2tlbg` |
| `pingId` | `cGluZ19maXh0dXJlX2lkIQ` |
| pairing key | `AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8` |
| synthetic phone address | `192.168.42.17` |
| synthetic TV address | `192.168.42.88` |
| synthetic TV label | `Demo TV` |
| synthetic device label | `Demo Phone` |
| synthetic title | `Demo clip` |

The address strings are synthetic documentation data; implementations require a currently owned RFC1918 address at runtime.

## Pre-auth frames

```json
{"t":"negotiate","v":2,"minV":2,"maxV":2,"clientNonce":"ERITFBUWFxgZGhscHR4fIA"}
{"t":"negotiated","v":2,"clientNonce":"ERITFBUWFxgZGhscHR4fIA","serverNonce":"ISIjJCUmJygpKissLS4vMA","tvId":"ABEiM0RVZneImaq7zN3u_w","cap":["cast-ack","first-frame-ready","structured-errors","resume-hmac"]}
{"t":"pair","v":2,"clientNonce":"ERITFBUWFxgZGhscHR4fIA","serverNonce":"ISIjJCUmJygpKissLS4vMA","code":"0007","device":"Demo Phone"}
{"t":"paired","v":2,"key":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","keyId":"AQIDBAUGBwgJCgsMDQ4PEA","tv":"Demo TV","tvId":"ABEiM0RVZneImaq7zN3u_w","peerIp":"192.168.42.17","serverHost":"192.168.42.88","serverPort":42421,"cap":["cast-ack","first-frame-ready","structured-errors","resume-hmac"]}
{"t":"denied","v":2}
```

`negotiate` has exactly `t,v,minV,maxV,clientNonce`; `minV=maxV=2`. `negotiated` does not include endpoint, key, key ID, code, or proof. `pair` does not include a host/port/key. `paired` has exactly the displayed fields: it is the only v2 message carrying the pairing key and it does **not** repeat negotiation nonces or add a proof. Authorization denial paths serialize only the final fixture above then close; malformed/policy input may instead consume the generic three-frame budget and close 1008 without a detailed oracle.

## Resume frames and fixed HMAC vector

```json
{"t":"resumeInit","v":2,"tvId":"ABEiM0RVZneImaq7zN3u_w","keyId":"AQIDBAUGBwgJCgsMDQ4PEA","clientNonce":"ERITFBUWFxgZGhscHR4fIA"}
{"t":"resumeChallenge","v":2,"tv":"Demo TV","tvId":"ABEiM0RVZneImaq7zN3u_w","keyId":"AQIDBAUGBwgJCgsMDQ4PEA","clientNonce":"ERITFBUWFxgZGhscHR4fIA","serverNonce":"ISIjJCUmJygpKissLS4vMA","peerIp":"192.168.42.17","serverHost":"192.168.42.88","serverPort":42421,"cap":["cast-ack","first-frame-ready","structured-errors","resume-hmac"]}
{"t":"resumeProof","v":2,"tvId":"ABEiM0RVZneImaq7zN3u_w","keyId":"AQIDBAUGBwgJCgsMDQ4PEA","clientNonce":"ERITFBUWFxgZGhscHR4fIA","serverNonce":"ISIjJCUmJygpKissLS4vMA","proof":"bqJZ6nUWl-KhUfA49f3Y9TWZ39boGj2P01YsmwTs53E"}
{"t":"resumed","v":2,"tv":"Demo TV","tvId":"ABEiM0RVZneImaq7zN3u_w","keyId":"AQIDBAUGBwgJCgsMDQ4PEA","clientNonce":"ERITFBUWFxgZGhscHR4fIA","serverNonce":"ISIjJCUmJygpKissLS4vMA","peerIp":"192.168.42.17","serverHost":"192.168.42.88","serverPort":42421,"cap":["cast-ack","first-frame-ready","structured-errors","resume-hmac"],"proof":"Z2JExw8mDA1QzUJGIira1xeQE3YvZiUbgl3jW9XK-Sk"}
```

For the client proof, concatenate four-byte unsigned big-endian length plus UTF-8 bytes for the following fields, with no delimiter or terminal byte:

```text
Flick-Control-Resume-V2
client
2
ABEiM0RVZneImaq7zN3u_w
AQIDBAUGBwgJCgsMDQ4PEA
ERITFBUWFxgZGhscHR4fIA
ISIjJCUmJygpKissLS4vMA
192.168.42.17
192.168.42.88
42421
Demo TV
cast-ack,first-frame-ready,structured-errors,resume-hmac
```

The resulting transcript has 260 bytes and is exactly:

```text
00000017466c69636b2d436f6e74726f6c2d526573756d652d563200000006636c69656e74000000013200000016414245694d3052565a6e65496d6171377a4e33755f770000001641514944424155474277674a4367734d4451345045410000001645524954464255574678675a47687363485234664941000000164953496a4a43556d4a7967704b6973734c5334764d410000000d3139322e3136382e34322e31370000000d3139322e3136382e34322e38380000000534323432310000000744656d6f20545600000038636173742d61636b2c66697273742d6672616d652d72656164792c737472756374757265642d6572726f72732c726573756d652d686d6163
```

`HmacSHA256` with the fixture pairing key yields client proof `bqJZ6nUWl-KhUfA49f3Y9TWZ39boGj2P01YsmwTs53E`. Replacing only the second field with `server` yields `Z2JExw8mDA1QzUJGIira1xeQE3YvZiUbgl3jW9XK-Sk`. This vector was independently computed by a standalone Ruby/OpenSSL scratch process, not production code. Change one field, its order, its byte length, encoding, role, or the key and verification must fail.

## Authenticated command frames

```json
{"t":"loadMedia","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg","url":"http://192.168.42.17:8080/v/ZGVtb19tZWRpYV90b2tlbg","title":"Demo clip","durationMs":123000,"startMs":0}
{"t":"play","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg"}
{"t":"pause","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg"}
{"t":"seek","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg","posMs":45000}
{"t":"skip","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg","deltaMs":-10000}
{"t":"skip","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg","deltaMs":10000}
{"t":"setVolume","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg","level":0.75}
{"t":"cancelLoad","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg"}
{"t":"stop","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg"}
{"t":"ping","v":2,"id":"cGluZ19maXh0dXJlX2lkIQ"}
```

## Authenticated event frames

```json
{"t":"loadAccepted","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg"}
{"t":"loadReady","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg","probeLatencyMs":42,"startupMs":1380}
{"t":"loadFailed","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg","code":"http_rejected","retryable":true,"httpStatus":503}
{"t":"state","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg","posMs":0,"durationMs":123000,"playing":true,"bufferedMs":9000,"phase":"playing","volume":1.0,"seq":8}
{"t":"error","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg","code":"decoder_init","retryable":false}
{"t":"error","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg","code":"http_rejected","retryable":true,"httpStatus":502}
{"t":"stopped","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg"}
{"t":"commandRejected","v":2,"castId":"MDEyMzQ1Njc4OWFiY2RlZg","command":"seek","code":"stale_cast"}
{"t":"pong","v":2,"id":"cGluZ19maXh0dXJlX2lkIQ"}
{"t":"busy","v":2,"reason":"active_cast"}
```

`httpStatus` is forbidden in every other frame and in non-HTTP failures. `busy` is the only authenticated event without `castId`; it applies to the connection before it can own a cast. V2 has no positive `available` fixture: implementations currently wait 250 ms for immediate `busy` after `paired`/`resumed`, then interpret silence as available. `stopped` is the exact replayable terminal result for canonical `stop` of a current Checking/Preparing or Active cast. `state.phase` permits only `buffering`, `playing`, `paused`, and `ended`.

## Negative fixtures and limits

The following categories must reject/close rather than silently coerce: top-level array/string/null; duplicate JSON key; unknown field; absent required field; `v:1`; `"v":"2"`; invalid base64url length; number outside specified bound; `NaN`/`Infinity`; URL with a query/fragment/user-info/redirect/noncanonical token path/wrong peer/wrong port; code that is not four ASCII digits; cap reordered/duplicated/unknown; stale cast command; binary or fragmented message; 16 KiB + 1 decoded bytes.

For test construction, exactly 16 KiB decoded UTF-8 input is eligible for schema parsing; 16 KiB plus one decoded byte closes 1009 before partial parsing. Pre-auth accepts at most three malformed frames under its existing unauthenticated budget, then closes 1008 generic. Authenticated malformed input may emit one safe `commandRejected` with `code:"malformed"`, then closes 1008. A valid but stale command emits `commandRejected` and keeps the authenticated session open.

## Failure-code fixture set

```text
update_required control_unreachable source_unavailable no_compatible_lan
media_bind_failed host_mismatch media_unreachable sender_not_serving
http_rejected tv_backgrounded malformed_media unsupported_container
unsupported_video_format unsupported_video_codec unsupported_hdr_profile
decoder_init startup_timeout control_disconnected active_cast_busy
protocol_error unknown
```

Only lowercase snake-case values from this set are valid terminal failure codes. All terminal `loadFailed` and `error` frames include `retryable`; retry is always explicit user action.
