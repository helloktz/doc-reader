# IconPark

Source: https://github.com/bytedance/IconPark
Revision: 8dc132da4c85671ba6a5962c87aa2bdafbf158e9
Authors: ByteDance and the IconPark contributors
License: Apache-2.0, see LICENSE in this directory.

Discovered through the IconPark collection on iconfont:
https://www.iconfont.cn/collections/detail?cid=48811
The upstream files are used rather than relying on a third-party upload's license.

`icons/` retains the nine original SVGs. The app uses their local Android vector
adaptations in `app/src/main/res/drawable/ic_*.xml`:

| Original upstream path | Android resource |
| --- | --- |
| source/Arrows/left.svg | ic_back |
| source/Arrows/download.svg | ic_export |
| source/Character/close-small.svg | ic_close |
| source/Edit/auto-width.svg | ic_fit |
| source/Edit/lock.svg | ic_lock |
| source/Edit/unlock.svg | ic_unlock |
| source/Edit/list-two.svg | ic_outline |
| source/Edit/grid-four.svg | ic_pages |
| source/Office/folder-open.svg | ic_open |

Modifications: converted SVG shapes to VectorDrawable paths; applied the reader's
ink, teal and mint palette; reduced strokes from 4 to 3.4 within the 48-unit
viewport; filled the list bullets and download tray; added a paper shape behind
the fit-width arrows. The launcher artwork remains original to this project.
No IconPark software runtime is included.
