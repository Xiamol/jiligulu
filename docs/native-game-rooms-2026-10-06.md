# Native game-room QA — 2026-10-06

APK SHA-256: `d6e91572568ab82e9c1ee91aa108d3174ef9d3f36fffbde52b573c59b2a4e486`

Devices: emulator-5580 and emulator-5582. Both ran the release APK. All actions were actual Android UI taps; no board fixture, private state insertion, proxy modification, or TLS bypass. Screenshots are unedited under `D:/叽里咕噜/output/android-qa/screenshots/`.

## Passed observations

- Gomoku Nearby: first device displayed radar; opening Nearby on the second auto-paired in about one second, with no manual partner/IP entry. 5580 was black, 5582 white. Both actually placed a stone and showed the same two-stone board and turn (`oct6-final-polish-network-go-two-host.png`, `...go-two-guest.png`).
- Xiangqi Nearby: radar and automatic pairing. 5580 was red, 5582 black. Red soldier (4,6)→(4,5), black soldier (4,3)→(4,4). The black device used its own-side-bottom view. Opponent selection was visibly dashed on the peer (`...xq-red-selection-remote.png`, `...xq-black-selection-remote.png`, `...xq-two-host.png`, `...xq-two-guest.png`).
- Public room-code transport: custom Gomoku room `ALU2334` was created. Duplicate creation on the other device displayed the occupied-code error (`...code-conflict.png`). Canceling the host released the code; it could be immediately recreated (`...code-reused.png`) and joined normally.
- Public Gomoku: three real nine-ply games. Black placed x=3..7,y=7; white x=3..6,y=5. Both devices correctly displayed win/loss, a brief effect, and persistent wooden result plates. After one requested rematch, the peer accepted through the real "好呀，换边再下" node. New board was empty and colors exchanged (5580 white, 5582 black), then another nine-ply game completed. After no rematch for 30 seconds, both terminal boards remained and the room was closed (`...online-round3-expired-host.png`, `...online-round3-expired-guest.png`). A new host could reuse the same code (`...round3-reuse-open.png`).
- Public Xiangqi room: real creation and join of `ALU2334`, then the same two legal soldier moves and synchronized opponent selections (`...xq-online-two-host.png`, `...xq-online-two-guest.png`). Accepting black's undo returned both boards to the one-ply state (`...xq-online-undo-accepted-host.png`, `...xq-online-undo-accepted-guest.png`). Replaying the black move and explicitly refusing a second undo kept both two-ply boards unchanged, with the refusal message (`...xq-undo-refused-host.png`, `...xq-undo-refused-guest.png`).
- Test rooms were explicitly closed through the leave confirmation. Both devices were returned to SecretBase (`...cleaned-host.png`, `...cleaned-guest.png`).

## Scope

These are native release UI checks of actual NSD and production PeerJS/WebRTC room flows on two emulators sharing the host network. They do not claim coverage of different physical-phone networks or every NAT configuration. Xiangqi network terminal/rematch was not artificially forced; its terminal lifecycle is covered by the automated Session/protocol tests. Gomoku network terminal/rematch/expiry was exercised end to end.

One initial rematch attempt and one initial undo dialog expired during tool/message delay; these were not reported as protocol defects or as successful refusal tests. The subsequent consent/refusal attempts ran within their deadlines and were checked on both screens.
