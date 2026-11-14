# Usage: python3 art/tatrak/check_contrast.py   (exit 1 if a pair fails)
# WCAG contrast of the Slate and Sea palette (knowledge/egm/style-slate-sea/design_draft.md): 4.5 for text, 3 for icons.
# Reuses cr() / mix() and the palette tables of gen_accents.py; importing it writes the generated files into a temp dir only.
import os,sys,tempfile
tmp=tempfile.mkdtemp()
for d in ('res/values','res/values-night','kotlin/am/cyberhayq/chat/ui/theme'): os.makedirs(os.path.join(tmp,d))
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
sys.argv=[sys.argv[0],os.path.join(tmp,'res')]
import gen_accents as g
W='FFFFFF'
def over(fg8,bg):  # an 8-digit AARRGGBB colour over an opaque background
    a=int(fg8[:2],16)/255; return g.mix(bg,fg8[2:],a)
# Warning bubble of ChatScreenColors (ChatPalette.kt), the same in both styles (day text darkened from B26A00, which was 3.77)
WARN={'day':('FFF1C9','8F5600'),'night':('4A3D12','FFC94D')}
fails=0
def check(label,fg,bg,need):
    global fails
    r=g.cr(fg,bg); ok=r>=need; fails+=not ok
    print(f"{'ok  ' if ok else 'FAIL'} {r:5.2f} (>= {need}) {label}")
for mode,S,A in (('day',g.SLATE_L,g.L['sea']),('night',g.SLATE_D,g.D['sea']),('night elegant',g.SLATE_ELEGANT,g.D['sea']))+tuple((f'{m} {a}',(g.NEU_L if m=='day' else g.NEU_N)[a],(g.L if m=='day' else g.D)[a]) for a in g.PALETTES for m in ('day','night')):
    print('---',mode)
    surfaces=('background','top_bar','bubble_in','chat_background','container','container_highest','surface_variant')
    for s in surfaces:
        check(f'text on {s}',S['text'],S[s],4.5)
        check(f'muted (on_surface_variant) on {s}',S['on_surface_variant'],S[s],4.5)
    check('top bar muted on top_bar',S['on_top_bar_muted'],S['top_bar'],4.5)
    check('time_in on bubble_in',S['time_in'],S['bubble_in'],4.5)
    check('text on outgoing bubble',S['text'],A['bubble_out'],4.5)
    check('time_out on outgoing bubble',A['time_out'],A['bubble_out'],4.5)
    for s,bg in (('bubble_out',A['bubble_out']),('bubble_in',S['bubble_in']),('background',S['background']),('divider',S['divider'])): check(f'link on {s}',A['link'],bg,4.5)
    check('badge text (white) on badge',W,A['badge'],4.5)
    check('button text on primary',A['on_primary'],A['primary'],4.5)
    check('primary (text buttons) on background',A['primary'],S['background'],4.5)
    check('check_read on outgoing bubble',A['check_read'],A['bubble_out'],3)
    check('accent_out on outgoing bubble',A['accent_out'],A['bubble_out'],3)
    check('accent_in on incoming bubble',A['accent_in'],S['bubble_in'],3)
    check('outline on background',S['outline'],S['background'],3)
    check('date chip text (white) on chip over slate wallpaper',W,over(S['service'],S['chat_background']),4.5)
    check('drawer header text (white)',W,S['drawer_header'],4.5)
    check('white icon on archive',W,S['archive'],3)
    check('warning text on warning bubble',WARN[mode.split()[0]][1],WARN[mode.split()[0]][0],4.5)
    # phase 2 (design_phase2.md): dock, floating search, composer pill (surfaceContainer), recording, cards, soft bubbles
    pill=S['container']
    check('dock label (not selected) on dock (top_bar)',S['on_top_bar_muted'],S['top_bar'],4.5)
    check('dock label (selected, primary) on dock',A['primary'],S['top_bar'],4.5)
    sel=g.mix(S['top_bar'],A['primary'],0.14)
    check('dock label (selected) on the selected pill',A['primary'],sel,4.5)
    check('compose icon (on_primary) on primary',A['on_primary'],A['primary'],3)
    check('floating search text on the search pill',S['on_surface_variant'],pill,4.5)
    check('composer placeholder on the pill',S['on_surface_variant'],pill,4.5)
    check('composer text on the pill',S['text'],pill,4.5)
    check('recording timer on the pill',S['text'],pill,4.5)
    check('recording slide hint on the pill',S['on_surface_variant'],pill,4.5)
    check('recording dot (E53935) on the pill',"E53935",pill,3)
    check('card ring (primary) on background',A['primary'],S['background'],3)
    check('soft bubble text on incoming',S['text'],S['bubble_in'],4.5)
    check('soft bubble text on outgoing',S['text'],A['bubble_out'],4.5)
    check('topic unread badge text (white) on primary badge',W,A['badge'],4.5)
    # informational: other ordinary accents keep their own colours on slate surfaces
    for a in ('blue','cyan','green','violet','pink','orange','red'):
        T=g.L[a] if mode.startswith('day') else g.D[a]
        print(f'     info {a}: link on bubble_in {g.cr(T["link"],S["bubble_in"]):.2f}, time_out on bubble_out {g.cr(T["time_out"],T["bubble_out"]):.2f}')
print('FAILED' if fails else 'all pairs pass'); sys.exit(1 if fails else 0)
