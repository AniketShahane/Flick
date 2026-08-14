# The pair block, measured live: it is layer 2, it is transient, and three escapes are now closed

**Date:** 2026-08-14 (morning) · **Method:** direct `adb` measurement on the reference home LAN
**while the block was active**, from both devices and a third host, plus the phone's own in-app
service-discovery state. No web research. Everything below is measured unless marked otherwise.

Research 03 re-read captured evidence and concluded the block was dynamic and pair-specific. This
run reproduced the failure and measured it at a layer 03 could not reach. 03's conclusion holds and
is now stronger; three of its candidate escapes are dead.

## What reproduced exactly

The phone and the TV were on the same AP, same BSSID, same 5 GHz channel, same /24 — and could not
reach each other, while each reached the gateway and a third host (a laptop) on the same AP at full
speed. That is 03's E4 + E5 unchanged, ten weeks later, on a stock ISP-supplied gateway.

## What is new

**N1 — the block is at layer 2, not ARP and not IPv4.** 03 inferred an ARP failure from
`Destination Host Unreachable`. Measured this time: ICMPv6 to the peer's **link-local** address —
which addresses the peer's MAC directly, with no ARP and no L3 routing — also failed, **in both
directions**, while the neighbour entry held a valid hardware address. TCP to a port the peer was
definitely listening on returned `No route to host`. So the AP is refusing to forward frames between
two stations. No protocol, port, or address family rides around it: IPv6, alternate ports and QUIC
are all the same dead path.

**N2 — multicast crosses while unicast does not.** During the block the phone's own NSD stack logged
`Handled response ... newInCache: true` for the receiver's `_flick._tcp` record, with a fresh TTL,
carrying the TV's address, port and `state=ready`. The phone could **see** the TV and could not
**reach** it. This is the mechanism behind the one-way ARP state 03 observed: ARP requests are
broadcast (they cross) and ARP replies are unicast (they are dropped). It is also a usable detection
fingerprint — see below.

**N3 — the failure has a fast, specific signature.** Blocked TCP connect: `EHOSTUNREACH` in **363 ms
cold, ~40 ms warm** (the kernel caches the negative resolution). A reachable peer with a closed port:
`ECONNREFUSED` in 40 ms. The distinction is therefore cheap and lands well inside the receiver's
2 s pre-flight budget — it does not degrade into an ambiguous timeout, which had been the main
worry about relying on it.

**N4 — the TV has no Wi-Fi Direct and no Wi-Fi Aware.** `pm list features` on the receiver returns
only `android.hardware.wifi` and `android.hardware.wifi.passpoint`; `dumpsys wifip2p` is empty. The
phone has `wifi.direct`, `wifi.aware` and `wifi.rtt`. **This is the test the research README calls
"the one test that gates the architecture", and the answer is no.** Wi-Fi Direct is not buildable on
this hardware, now or later, and no amount of app work changes that.

**N5 — no UPnP IGD answered** an SSDP `M-SEARCH` for `InternetGatewayDevice` on this LAN.

**N6 — only the randomised-MAC devices were blocked.** Of six hosts on the LAN, exactly two used
locally-administered (randomised) MACs — the phone and the TV — and they were the only pair that
could not talk. The other four used vendor OUIs and reached everything. **n = 2 and confounded**
(they are also the only two Android devices), so this is a lead, not a finding. It is cheap to test:
the per-network "Use device MAC" privacy setting changes a station's identity persistently, keeps
the saved network, and needs no password.

**N7 — the block is transient and cleared with no user action.** It spanned roughly overnight to
mid-morning, then healed on its own: the pair went to 0% loss at 2–10 ms round-trip and a 36 ms TCP
connect, with the owner having changed nothing. **What cleared it is unattributed.** A heavy
bidirectional probe burst (dozens of ICMP and TCP attempts from *both* ends, including unicast from
the TV toward the phone, which held a valid neighbour entry throughout) ran within ~10 minutes of the
recovery. That is a suspicious coincidence and the single highest-value thing to test next time —
but it is a coincidence, not a demonstrated cause, and it must not be written up as one.

## The detection fingerprint

From N2 + N3, the sender can classify this without any wire change, any new permission, or any
receiver change — every input already exists (`dialDiagnosis` → `DialFault.NO_ROUTE`,
`NsdDiscovery.refresh`, `LanProximity.sameSubnetClaim`, plus a `NET_CAPABILITY_VALIDATED` callback):

> phone's own Wi-Fi validated, signal not marginal, the receiver **freshly** present over mDNS in
> this pass, same subnet — **and** the dial returns `NO_ROUTE`.

`REFUSED` must never count toward it: an RST proves forwarding works and the receiver simply is not
listening. The freshness gate matters because the platform's NSD stack can answer from cache, so a
TV powered off minutes ago can otherwise look present.

## Closed for good — do not re-open

| Candidate | Status |
|---|---|
| Wi-Fi Direct / Wi-Fi Aware | **Dead.** Not present on the receiver (N4). Closes 03's candidate 3 and its step 6, and the README's gating test. |
| UPnP IGD hairpin | **Dead.** No IGD (N5); it would publish a WAN-reachable port serving the owner's private files; and it contradicts the receiver's own host/port pinning, so the fetch would fail validation by design. |
| Alternate ports / IPv6 / QUIC | **Dead.** The block is below all of them (N1). |
| App-driven Wi-Fi reconnect | **Dead.** `setWifiEnabled`, `disconnect`, `reconnect`, `reassociate` are no-ops for non-privileged apps targeting API 29+. |
| Suggestion-API MAC control | **Dead.** Applies only to networks the app itself suggests; needs credentials the app cannot read. |
| ARP / raw sockets / ICMP in-app | **Dead.** Privileged since Android 10. None of the evidence in this document is reproducible from inside the app; the fingerprint above is the whole observable universe. |
| Relay via a third host | Viable but needs an always-on box most owners lack. Debugging tool, not a product path. |

## What is left, honestly

Nothing an app can do crosses the block. What it can do is stop being a mystery: classify the state,
say plainly that the router is refusing to carry traffic between these two devices, and offer the one
path that cannot be blocked — the phone's own hotspot, which removes the router from the path by
construction. The receiver has no Wi-Fi Direct (N4), so joining that hotspot can never be automated;
it is a manual step on the TV's own screen, every session, and the copy must say so.

Wiring the TV to Ethernet remains the cleanest permanent fix for a household that can run a cable,
and it also removes the 5 GHz VBR headroom problem.

## The experiment for next time

The block recurs. When it does, before touching anything:

1. Open the app's device list. If the receiver shows as ready while the dial fails, N2's fingerprint
   is confirmed a second time on this router.
2. Then run a bounded bidirectional probe burst and time it. If the pair recovers, N7's coincidence
   becomes a mechanism — and a recovery an app could perform silently in seconds. If it does not,
   N7 was just the block expiring, and patience plus the hotspot are the whole answer.
