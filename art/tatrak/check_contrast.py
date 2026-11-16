# Usage: python3 art/tatrak/check_contrast.py [-v]   (exit 1 if a pair fails; -v prints every pair)
# WCAG contrast of every accent on every surface it is drawn on: 9 accents x {Telegram, Slate and Sea} x {day, night blue-grey,
# night graphite, night AMOLED}. 4.5 for text, 3 for icons, badges and UI parts.
# Reuses cr() / mix() and the colour tables of gen_accents.py; importing it writes the generated files into a temp dir only.
import os,sys,tempfile
verbose='-v' in sys.argv
tmp=tempfile.mkdtemp()
for d in ('res/values','res/values-night','kotlin/am/cyberhayq/chat/ui/theme'): os.makedirs(os.path.join(tmp,d))
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
sys.argv=[sys.argv[0],os.path.join(tmp,'res')]
import gen_accents as g
W='FFFFFF'
def fab_fill(primary): return g.fit(primary,W,4.5)  # fabFill() in Theme.kt: primary darkened until white reaches 4.5:1
def over(fg8,bg):  # an 8-digit AARRGGBB colour over an opaque background
    a=int(fg8[:2],16)/255; return g.mix(bg,fg8[2:],a)
# Warning bubble of ChatScreenColors (ChatPalette.kt), the same in both styles (day text darkened from B26A00, which was 3.77)
WARN={'day':('FFF1C9','8F5600'),'night':('4A3D12','FFC94D')}
MODES={('telegram','day'):g.TELEGRAM_L,('telegram','blue-grey'):g.TELEGRAM_D,('telegram','graphite'):g.GRAPHITE,('telegram','amoled'):g.AMOLED,
       ('slate','day'):g.SLATE_L,('slate','blue-grey'):g.SLATE_D,('slate','graphite'):g.SLATE_GRAPHITE,('slate','amoled'):g.SLATE_AMOLED}
TEXT_SURFACES=('background','top_bar','bubble_in','chat_background','container','container_highest','surface_variant')
MUTED_SURFACES=('background','top_bar','bubble_in','container','container_highest','surface_variant')  # muted text is never drawn on the bare wallpaper
fails=0; total=0; low={}  # low[(style,mode,label)] = (lowest ratio, accent)
def check(key,label,fg,bg,need,who=''):
    global fails,total
    r=g.cr(fg,bg); ok=r>=need; fails+=not ok; total+=1
    k=key+(label,)
    if k not in low or r<low[k][0]: low[k]=(r,who,need)
    if verbose or not ok: print(f"{'ok  ' if ok else 'FAIL'} {r:5.2f} (>= {need}) {' / '.join(key)} {who} {label}")
info=[]
for (style,mode),S in MODES.items():
    key=(style,mode); day=mode=='day'
    for s in TEXT_SURFACES: check(key,'text on surfaces',S['text'],S[s],4.5,s)
    for s in MUTED_SURFACES: check(key,'muted text on surfaces',S['on_surface_variant'],S[s],4.5,s)
    check(key,'top bar muted on top bar',S['on_top_bar_muted'],S['top_bar'],4.5)
    check(key,'time_in on incoming bubble',S['time_in'],S['bubble_in'],4.5)
    check(key,'warning text on warning bubble',WARN['day' if day else 'night'][1],WARN['day' if day else 'night'][0],4.5)
    if 'outline' in S:  # the generated sets (the Telegram defaults keep Material's outline)
        check(key,'outline on background',S['outline'],S['background'],3)
        check(key,'date chip text (white) on chip over the chat background',W,over(S['service'],S['chat_background']),4.5)
        check(key,'drawer header text (white)',W,S['drawer_header'],4.5)
        check(key,'white icon on archive',W,S['archive'],3)
    for a,_ in g.ACC:
        A=(g.L if day else g.D)[a]
        check(key,'text on outgoing bubble',S['text'],A['bubble_out'],4.5,a)
        check(key,'time_out on outgoing bubble',A['time_out'],A['bubble_out'],4.5,a)
        for s,bg in (('background',S['background']),('bubble_in',S['bubble_in']),('bubble_out',A['bubble_out'])): check(key,f'link on {s}',A['link'],bg,4.5,a)
        check(key,'button text (on_primary) on primary',A['on_primary'],A['primary'],4.5,a)
        check(key,'primary on background',A['primary'],S['background'],3,a)
        check(key,'badge text (contentOn) on badge',g.glyph(A['badge']),A['badge'],3,a)
        check(key,'glyph (contentOn) on primary',g.glyph(A['primary']),A['primary'],3,a)
        # owner's choice (2026-11-15): white pencil on a FAB filled with the primary darkened to 4.5:1 (fabFill() in Theme.kt)
        check(key,'FAB white pencil on fabFill(primary)',W,fab_fill(A['primary']),4.5,a)
        check(key,'check_read on outgoing bubble',A['check_read'],A['bubble_out'],3,a)
        check(key,'accent_out on outgoing bubble',A['accent_out'],A['bubble_out'],3,a)
        check(key,'accent_in on incoming bubble',A['accent_in'],S['bubble_in'],3,a)
        check(key,'round file button glyph (contentOn) on accent_in',g.glyph(A['accent_in']),A['accent_in'],3,a)
        if style=='telegram' and day: check(key,'drawer header text (white) on primary_container',W,A['primary_container'],4.5,a)
        # reaction chips (MessageBubble Reactions): mine = contentOn count on the accent fill (3, as badge counts);
        # not mine = the accent count on the accent at 14 % over the bubble
        for side,acc,bub in (('incoming','accent_in',S['bubble_in']),('outgoing','accent_out',A['bubble_out'])):
            check(key,f'reaction count, mine, on {side} chip',g.glyph(A[acc]),A[acc],3,a)
            check(key,f'reaction count, not mine, on {side} chip',A[acc],g.mix(bub,A[acc],0.14),3,a)
        if style=='slate':  # the dock (ChatListDock): selected = link, on the top bar (label) and on its 14 % pill (icon)
            check(key,'dock label (selected, link) on dock',A['link'],S['top_bar'],4.5,a)
            check(key,'dock icon (selected, link) on the selected pill',A['link'],g.mix(S['top_bar'],A['link'],0.14),3,a)
        # informational: primary as text (text buttons, section titles) wants 4.5, the check above asks 3
        r=min(g.cr(A['primary'],S[s]) for s in ('background','container','top_bar'))
        if r<4.5: info.append(f'{style}/{mode} {a}: primary as text {r:.2f}')
    if style=='slate':  # phase 2 (design_phase2.md): dock, floating search, composer pill, recording (neutral parts)
        pill=S['container']
        check(key,'dock label (not selected) on dock',S['on_top_bar_muted'],S['top_bar'],4.5)
        check(key,'composer text and placeholder on the pill',S['on_surface_variant'],pill,4.5)
        check(key,'recording dot (E53935) on the pill','E53935',pill,3)
# summary: lowest ratio per pair kind and surface set
print(f'{"style / mode":22} {"lowest ratio (need) pair":60} accent / surface')
for (style,mode,label),(r,who,need) in sorted(low.items(),key=lambda kv:(kv[0][0],list(MODES).index(kv[0][:2]),kv[0][2])):
    print(f'{style+" / "+mode:22} {r:5.2f} ({need}) {label:47} {who}')
print('\ninformational, primary as text below 4.5 (not a failure):'); print('\n'.join('  '+i for i in info) or '  none')
print(f'\n{total} pairs, ', end=''); print(f'{fails} FAILED' if fails else 'all pairs pass'); sys.exit(1 if fails else 0)
