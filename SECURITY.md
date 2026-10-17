# Security policy

Security is the point of this project, so reports are very welcome.

## Reporting a vulnerability

Please **do not open a public issue** for a vulnerability. Use GitHub's
*Security → Report a vulnerability* (private advisory) on this repository instead, with:

- what an attacker can do, and from where (another program on the PC, a web page, the LAN, a
  file / page / screenshot the model reads, the Colab tunnel …);
- steps to reproduce, and the Haris version (`pom.xml`).

You should get an answer within a week. Fixes are released before details are published.

What Haris defends against, and what it does not, is written down in [THREAT_MODEL.md](THREAT_MODEL.md).

## In scope

- Reaching the local server from anything other than the Haris window (other programs, web pages,
  other devices, DNS rebinding, cross-site requests, WebSocket hijacking).
- Getting a tool to run without an approval click, or with an approval for something else than shown.
- Prompt injection that leads to an action or data leaving the PC without the confirmation the
  README promises.
- Reading or writing files outside the folders the user granted; reading stored keys back.
- SSRF from `fetch_page` / `web_search` into the LAN, loopback or cloud metadata.
- Weaknesses in the encrypted memory or the Colab proxy's token check.

## Out of scope

- An attacker who already runs code as your Windows user (they can read your keys from Credential
  Manager anyway).
- What a cloud provider you chose does with your data — see the privacy table in the README.
- Denial of service against your own machine.
